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

package com.github.levkhomich.akka.tracing.http

import akka.actor.ActorSystem
import spray.client.pipelining._
import spray.http.{ HttpResponse, HttpRequest }

import com.github.levkhomich.akka.tracing._

case class TracedClientRequest() extends TracingSupport

trait TracedSprayPipeline {

  val system: ActorSystem
  def sendAndReceive: SendReceive

  implicit lazy val trace: TracingExtensionImpl = TracingExtension(system)
  import system.dispatcher

  def tracedPipeline[T](trigger: BaseTracingSupport) = {
    val clientReq = TracedClientRequest().asChildOf(trigger)
    addHeader("X-B3-TraceId", SpanMetadata.idToString(clientReq.tracingId))
    addHeader("X-B3-Sampled", trace.getId(clientReq.tracingId).isDefined.toString) ~>
      startTrace(clientReq) ~>
      sendAndReceive ~>
      completeTrace(clientReq)
  }

  def startTrace(clientReq: BaseTracingSupport)(req: HttpRequest): HttpRequest = {
    trace.record(clientReq, thrift.zipkinConstants.CLIENT_SEND)
    trace.record(clientReq, s"requesting to ${req.uri}")
    req
  }

  def completeTrace(clientReq: BaseTracingSupport)(response: HttpResponse): HttpResponse = {
    trace.record(clientReq, s"response code ${response.status}")
    trace.finishChildRequest(clientReq)
    response
  }
}
