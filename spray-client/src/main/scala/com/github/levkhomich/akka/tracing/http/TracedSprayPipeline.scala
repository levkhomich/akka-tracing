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

import java.nio.ByteBuffer

import akka.actor.ActorSystem
import spray.client.pipelining._
import spray.http.{ HttpHeaders, HttpResponse, HttpRequest }

import com.github.levkhomich.akka.tracing._

trait TracedSprayPipeline {

  val system: ActorSystem
  def sendAndReceive: SendReceive

  implicit lazy val trace: TracingExtensionImpl = TracingExtension(system)
  import system.dispatcher

  def tracedPipeline[T](parent: BaseTracingSupport) = {
    val clientRequest = new TracingSupport {}
    trace.createChild(clientRequest, parent, None).map(metadata =>
      addHeaders(List(
        HttpHeaders.RawHeader(TracingHeaders.TraceId, SpanMetadata.idToString(metadata.traceId)),
        HttpHeaders.RawHeader(TracingHeaders.SpanId, SpanMetadata.idToString(metadata.spanId)),
        HttpHeaders.RawHeader(TracingHeaders.ParentSpanId, SpanMetadata.idToString(metadata.parentId.get)),
        HttpHeaders.RawHeader(TracingHeaders.Sampled, "true")
      )) ~>
        startTrace(clientRequest) ~>
        sendAndReceive ~>
        completeTrace(clientRequest)
    ).getOrElse(sendAndReceive)
  }

  def startTrace(ts: BaseTracingSupport)(request: HttpRequest): HttpRequest = {
    trace.record(ts, thrift.zipkinConstants.CLIENT_SEND)

    // TODO: use `recordKeyValue` call after tracedComplete will be removed
    @inline def recordKeyValue(key: String, value: String): Unit =
      trace.addBinaryAnnotation(ts.tracingId, key, ByteBuffer.wrap(value.getBytes), thrift.AnnotationType.STRING)

    recordKeyValue("client-request.uri", request.uri.toString())
    recordKeyValue("client-request.proto", request.protocol.value)
    request.headers.foreach { header =>
      recordKeyValue("client-request.headers." + header.name, header.value)
    }

    request
  }

  def completeTrace(ts: BaseTracingSupport)(response: HttpResponse): HttpResponse = {
    trace.recordKeyValue(ts, "response.code", response.status.toString())
    trace.addAnnotation(ts.tracingId, thrift.zipkinConstants.CLIENT_RECV, send = true)
    response
  }
}
