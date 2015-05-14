package com.github.levkhomich.akka.tracing.http

import spray.http.{ HttpResponse, HttpRequest }
import spray.client.pipelining._
import akka.actor.ActorSystem
import com.github.levkhomich.akka.tracing._

case class TracedClientRequest() extends TracingSupport

trait TracedSprayPipeline {

  val system: ActorSystem
  def sendAndReceive: SendReceive

  implicit lazy val trace: TracingExtensionImpl = TracingExtension(system)
  import system.dispatcher

  def tracedPipeline[T](trigger: BaseTracingSupport) = {
    val clientReq = TracedClientRequest().asChildOf(trigger)
    addHeader("X-B3-TraceId", Span.asString(clientReq.tracingId))
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
