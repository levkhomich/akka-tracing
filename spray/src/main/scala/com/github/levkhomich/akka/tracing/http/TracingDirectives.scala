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

import scala.util.{ Failure, Random, Try, Success }

import akka.actor.Actor
import shapeless._
import spray.http.{ HttpMessage, HttpRequest, HttpResponse }
import spray.httpx.marshalling._
import spray.httpx.unmarshalling._
import spray.routing._

import com.github.levkhomich.akka.tracing._

trait BaseTracingDirectives {

  protected def trace: TracingExtensionImpl

  import spray.routing.directives.BasicDirectives._
  import spray.routing.directives.RouteDirectives._
  import spray.routing.directives.MiscDirectives._
  import TracingDirectives._

  private[this] def tracedEntity[T <: TracingSupport](service: String)(implicit um: FromRequestUnmarshaller[T]): Directive[T :: HNil] =
    hextract(ctx => ctx.request.as(um) :: extractSpan(ctx.request) :: ctx.request :: HNil).hflatMap[T :: HNil] {
      case Right(value) :: Right(optSpan) :: request :: HNil =>
        optSpan.foreach(s => value.init(s.$spanId, s.$traceId.get, s.$parentId))
        if (optSpan.map(_.forceSampling).getOrElse(false))
          trace.forcedSample(value, service)
        else
          trace.sample(value, service)
        addHttpAnnotations(value, request)
        hprovide(value :: HNil)
      case Right(value) :: Left(headerName) :: request :: HNil =>
        reject(MalformedHeaderRejection(headerName, "invalid value"))
      case Left(ContentExpected) :: _ => reject(RequestEntityExpectedRejection)
      case Left(UnsupportedContentType(supported)) :: _ => reject(UnsupportedRequestContentTypeRejection(supported))
      case Left(MalformedContent(errorMsg, cause)) :: _ => reject(MalformedRequestContentRejection(errorMsg, cause))
    } & cancelAllRejections(ofTypes(RequestEntityExpectedRejection.getClass, classOf[UnsupportedRequestContentTypeRejection]))

  /**
   * Completes the request using the given function. The input to the function is
   * produced with the in-scope entity unmarshaller and the result value of the
   * function is marshalled with the in-scope marshaller. Unmarshalled entity is
   * sampled for tracing and can be used thereafter to add trace annotations.
   * RPC name is set to unmarshalled entity simple class name.
   * After marshalling step, trace is automatically closed and sent to collector service.
   * tracedHandleWith can be a convenient method combining entity with complete.
   *
   * @param service service name to be added to trace
   */
  def tracedHandleWith[A <: TracingSupport, B](service: String)(f: A => B)(implicit um: FromRequestUnmarshaller[A], m: ToResponseMarshaller[B]): Route =
    tracedEntity(service)(um) {
      case ts =>
        new StandardRoute {
          def apply(ctx: RequestContext): Unit =
            ctx.complete(f(ts))(traceServerSend(ts))
        }
    }

  /**
   * Completes the request using the given argument(s). Traces server receive and
   * send events, supports requests with tracing-specific headers.
   *
   * @param service service name to be added to trace
   * @param rpc RPC name to be added to trace
   */
  def tracedComplete[T](service: String, rpc: String)(value: => T)(implicit m: ToResponseMarshaller[T]): StandardRoute =
    new StandardRoute {
      def apply(ctx: RequestContext): Unit = {
        extractSpan(ctx.request) match {
          case Right(Some(span)) =>
            // only requests with explicit tracing headers can be traced here, because we don't have
            // any clues about spanId generated for unmarshalled entity
            if (span.forceSampling)
              trace.forcedSample(span, service, rpc)
            else
              trace.sample(span, service, rpc)
            addHttpAnnotations(span, ctx.request)
            ctx.complete(value)(traceServerSend(span))

          case _ =>
            ctx.complete(value)
        }
      }
    }

  private[this] def addHttpAnnotations(ts: BaseTracingSupport, request: HttpRequest): Unit = {
    // TODO: use batching
    trace.recordKeyValue(ts, "request.uri", request.uri.toString())
    trace.recordKeyValue(ts, "request.path", request.uri.path.toString())
    trace.recordKeyValue(ts, "request.method", request.method.name)
    trace.recordKeyValue(ts, "request.proto", request.protocol.value)
    request.uri.query.toMultiMap.foreach {
      case (key, values) =>
        values.foreach(trace.recordKeyValue(ts, "request.query." + key, _))
    }
    request.headers.foreach { header =>
      trace.recordKeyValue(ts, "request.headers." + header.name, header.value)
    }
  }

  private[this] def traceServerSend[T](ts: BaseTracingSupport)(implicit m: ToResponseMarshaller[T]): ToResponseMarshaller[T] =
    new ToResponseMarshaller[T] {
      override def apply(value: T, ctx: ToResponseMarshallingContext): Unit = {
        val result = value
        m.apply(result, new DelegatingToResponseMarshallingContext(ctx) {
          override def marshalTo(entity: HttpResponse): Unit = {
            super.marshalTo(entity)
            trace.finish(ts)
          }
        })
      }
    }

}

trait TracingDirectives extends BaseTracingDirectives { this: Actor with ActorTracing =>

  /**
   * Completes the request using the given function. The input to the function is
   * produced with the in-scope entity unmarshaller and the result value of the
   * function is marshalled with the in-scope marshaller. Unmarshalled entity is
   * sampled for tracing and can be used thereafter to add trace annotations.
   * RPC name is set to unmarshalled entity simple class name. Service name is set to
   * HTTP service actor's name. After marshalling step, trace is automatically closed
   * and sent to collector service. tracedHandleWith can be a convenient method
   * combining entity with complete.
   */
  def tracedHandleWith[A <: TracingSupport, B](f: A => B)(implicit um: FromRequestUnmarshaller[A], m: ToResponseMarshaller[B]): Route =
    tracedHandleWith(self.path.name)(f)

  /**
   * Completes the request using the given argument(s). Traces server receive and
   * send events, supports requests with tracing-specific headers. Service name is set to HTTP service actor's name.
   *
   * @param rpc RPC name to be added to trace
   */
  def tracedComplete[T](rpc: String)(value: => T)(implicit m: ToResponseMarshaller[T]): StandardRoute =
    tracedComplete(self.path.name, rpc)(value)

}

private[http] object TracingDirectives {

  import TracingHeaders._

  private[this] val DebugFlag = 1L

  def extractSpan(message: HttpMessage): Either[String, Option[Span]] = {
    def headerStringValue(name: String): Option[String] =
      message.headers.find(_.name == name).map(_.value)
    def headerLongValue(name: String): Either[String, Option[Long]] =
      Try(headerStringValue(name).map(Span.fromString)) match {
        case Failure(e) =>
          Left(name)
        case Success(v) =>
          Right(v)
      }
    def isFlagSet(v: String, flag: Long): Boolean =
      (java.lang.Long.parseLong(v) & flag) == flag
    // debug flag forces sampling (see http://git.io/hdEVug)
    def forceSampling: Boolean =
      headerStringValue(Flags).exists(isFlagSet(_, DebugFlag)) ||
        headerStringValue(Sampled).filter(_ == "true").isDefined
    def spanId: Long =
      headerLongValue(SpanId).right.toOption.flatten.getOrElse(Random.nextLong)

    headerLongValue(TraceId).right.map({
      case Some(traceId) =>
        headerLongValue(ParentSpanId).right.map { parentId =>
          Some(Span(traceId, spanId, parentId, forceSampling))
        }
      case None if forceSampling =>
        Right(Some(Span(Random.nextLong, spanId, None, true)))
      case _ =>
        Right(None)
    }).joinRight
  }

}

