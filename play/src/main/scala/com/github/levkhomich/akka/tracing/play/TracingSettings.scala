/**
 * Copyright 2014 the Akka Tracing contributors. See AUTHORS for more details.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.levkhomich.akka.tracing.play

import scala.concurrent.Future

import play.api.GlobalSettings
import play.api.libs.iteratee.Iteratee
import play.api.mvc._

import com.github.levkhomich.akka.tracing.{ TracingAnnotations, SpanMetadata }

trait TracingSettings extends GlobalSettings with PlayControllerTracing {

  lazy val serviceName = play.libs.Akka.system.name

  lazy val excludedQueryParams = Set.empty[String]

  lazy val excludedHeaders = Set.empty[String]

  protected def sample(request: RequestHeader): Unit = {
    def headers(name: String): Option[String] =
      request.headers.get(name)
    SpanMetadata.extractSpan(headers, requireTraceId = false) match {
      case Right(None) =>
      case Right(Some(span)) =>
        trace.sample(request.tracingId, span.spanId, span.parentId, span.traceId, serviceName, request.spanName, span.forceSampling)
      case Left(_) =>
        trace.sample(request.tracingId, serviceName, request.spanName)
    }
  }

  protected def addHttpAnnotations(request: RequestHeader): Unit = {
    // TODO: use batching
    trace.recordKeyValue(request, "request.path", request.path)
    trace.recordKeyValue(request, "request.method", request.method)
    trace.recordKeyValue(request, "request.secure", request.secure)
    trace.recordKeyValue(request, "request.proto", request.version)
    trace.recordKeyValue(request, "client.address", request.remoteAddress)
    // TODO: separate cookie records
    request.queryString.filter {
      case (key, values) =>
        !excludedQueryParams(key)
    }.foreach {
      case (key, values) =>
        values.foreach(trace.recordKeyValue(request, "request.query." + key, _))
    }
    request.headers.toMap.filter {
      case (key, values) =>
        !excludedHeaders(key)
    }.foreach {
      case (key, values) =>
        values.foreach(trace.recordKeyValue(request, "request.headers." + key, _))
    }
  }

  protected def requestTraced(request: RequestHeader): Boolean =
    !request.path.startsWith("/assets")

  protected class TracedAction(delegateAction: EssentialAction) extends EssentialAction with RequestTaggingHandler {
    override def apply(request: RequestHeader): Iteratee[Array[Byte], Result] = {
      if (requestTraced(request)) {
        sample(request)
        addHttpAnnotations(request)
      }
      delegateAction(request)
    }

    override def tagRequest(request: RequestHeader): RequestHeader = {
      request
    }
  }

  override def onRouteRequest(request: RequestHeader): Option[Handler] =
    super.onRouteRequest(request).map {
      case alreadyTraced: TracedAction =>
        alreadyTraced
      case action: EssentialAction =>
        new TracedAction(action)
      case handler =>
        handler
    }

  override def onRequestCompletion(request: RequestHeader): Unit = {
    trace.record(request, TracingAnnotations.ServerSend)
    super.onRequestCompletion(request)
  }

  override def onError(request: RequestHeader, ex: Throwable): Future[Result] = {
    trace.record(request, ex)
    super.onError(request, ex)
  }

}
