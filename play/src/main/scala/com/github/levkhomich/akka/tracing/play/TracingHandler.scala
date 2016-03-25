package com.github.levkhomich.akka.tracing.play

import javax.inject.Inject

import akka.actor.ActorSystem
import akka.util.ByteString
import com.github.levkhomich.akka.tracing.SpanMetadata
import com.github.levkhomich.akka.tracing.http.TracingHeaders
import play.api.http.{ HttpErrorHandler, HttpRequestHandler }
import play.api.libs.streams.Accumulator
import play.api.mvc._
import play.api.routing.Router

import scala.concurrent.Future
import play.api.mvc.Results._

import scala.util.Random

//todo onRequestCompletion

class TracingRequestHandler @Inject() (router: Router, actorSystem: ActorSystem) extends HttpRequestHandler with PlayControllerTracing {

  lazy val serviceName = actorSystem.name

  lazy val excludedQueryParams = Set.empty[String]

  lazy val excludedHeaders = Set.empty[String]

  def handlerForRequest(request: RequestHeader) = {
    sample(request)
    trace.start(request, serviceName)
    trace.record(request, serviceName)
    trace.flush(request)

    router.routes.lift(request) match {
      case alreadyTagged: EssentialAction with RequestTaggingHandler =>
        (request, new TracedAction(alreadyTagged) {
          override def tagRequest(request: RequestHeader): RequestHeader =
            super.tagRequest(alreadyTagged.tagRequest(request))
        })

      case action: EssentialAction =>
        (request, new TracedAction(action))

      case Some(handler) =>
        (request, handler)

      case _ =>
        (request, null)
    }
  }

  protected class TracedAction(delegateAction: EssentialAction) extends EssentialAction with RequestTaggingHandler {
    override def apply(request: RequestHeader): Accumulator[ByteString, Result] = {
      if (requestTraced(request)) {
        sample(request)
        addHttpAnnotations(request)
      }
      delegateAction(request)
    }

    override def tagRequest(request: RequestHeader): RequestHeader = {
      if (requestTraced(request))
        request.copy(tags = request.tags ++ extractTracingTags(request))
      else
        request
    }
  }

  protected def requestTraced(request: RequestHeader): Boolean =
    !request.path.startsWith("/assets")

  protected def sample(request: RequestHeader): Unit = {
    trace.sample(request, serviceName)
  }

  protected def extractTracingTags(request: RequestHeader): Map[String, String] = {
    val spanId = TracingHeaders.SpanId -> SpanMetadata.idToString(Random.nextLong)
    if (request.headers.get(TracingHeaders.TraceId).isEmpty)
      Map(TracingHeaders.TraceId -> SpanMetadata.idToString(Random.nextLong)) + spanId
    else
      TracingHeaders.All.flatMap(header =>
        request.headers.get(header).map(header -> _)).toMap + spanId
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
}

class TracingErrorHandler extends HttpErrorHandler with PlayControllerTracing {

  def onClientError(request: RequestHeader, statusCode: Int, message: String) = {
    trace.start(request, message)
    trace.record(request, message)
    trace.flush(request)
    Future.successful(
      Status(statusCode)("A client error occurred: " + message)
    )
  }

  def onServerError(request: RequestHeader, exception: Throwable) = {
    trace.start(request, exception.getLocalizedMessage)
    trace.record(request, exception)
    trace.flush(request)
    Future.successful(
      InternalServerError("A server error occurred: " + exception.getMessage)
    )
  }
}
