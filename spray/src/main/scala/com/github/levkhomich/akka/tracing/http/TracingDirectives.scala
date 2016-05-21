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
import scala.util.Random

import akka.actor.Actor
import shapeless._
import spray.http.{ HttpRequest, HttpResponse }
import spray.httpx.marshalling._
import spray.httpx.unmarshalling._
import spray.routing._

import com.github.levkhomich.akka.tracing._

trait BaseTracingDirectives {

  protected def trace: TracingExtensionImpl

  import spray.routing.directives.BasicDirectives._
  import spray.routing.directives.RouteDirectives._
  import spray.routing.directives.MiscDirectives._
  import spray.routing.directives.MarshallingDirectives._

  private[this] def tracedEntity[T <: TracingSupport](service: String)(implicit um: FromRequestUnmarshaller[T]): Directive[T :: HNil] =
    (entity(um) & requestInstance).hflatMap {
      case value :: request :: HNil =>
        def headers(name: String): Option[String] =
          request.headers.find(_.name == name).map(_.value)
        SpanMetadata.extractSpan(headers, requireTraceId = false) match {
          case Right(maybeSpan) =>
            maybeSpan.foreach { span =>
              trace.sample(value, span.spanId, span.parentId, span.traceId, service, span.forceSampling)
              addHttpAnnotations(value.tracingId, request)
            }
            provide(value)
          case Left(malformedHeaderName) =>
            reject(MalformedHeaderRejection(malformedHeaderName, "invalid value"))
        }
    }

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
            ctx.complete(f(ts))(traceServerSend(ts.tracingId))
        }
    }

  /**
   * Completes the request using the given argument(s). Traces server receive and
   * send events, supports requests with tracing-specific headers.
   *
   * @param service service name to be added to trace
   * @param rpc RPC name to be added to trace
   */
  @deprecated("use tracedHandleWith directive instead", "0.5")
  def tracedComplete[T](service: String, rpc: String)(value: => T)(implicit m: ToResponseMarshaller[T]): StandardRoute =
    new StandardRoute {
      def apply(ctx: RequestContext): Unit = {
        def headers(name: String): Option[String] =
          ctx.request.headers.find(_.name == name).map(_.value)
        SpanMetadata.extractSpan(headers, requireTraceId = true) match {
          case Right(Some(span)) =>
            // only requests with explicit tracing headers can be traced here, because we don't have
            // any clues about spanId generated for unmarshalled entity
            val tracingId = Random.nextLong()
            trace.sample(tracingId, span.spanId, span.parentId, span.traceId, service, rpc, span.forceSampling)
            addHttpAnnotations(tracingId, ctx.request)
            ctx.complete(value)(traceServerSend(tracingId))

          case _ =>
            ctx.complete(value)
        }
      }
    }

  private[this] def addHttpAnnotations(tracingId: Long, request: HttpRequest): Unit = {
    // TODO: use `recordKeyValue` call after tracedComplete will be removed
    @inline def recordKeyValue(key: String, value: String): Unit =
      trace.addBinaryAnnotation(tracingId, key, ByteBuffer.wrap(value.getBytes), thrift.AnnotationType.STRING)

    // TODO: use batching
    recordKeyValue("request.uri", request.uri.toString())
    recordKeyValue("request.path", request.uri.path.toString())
    recordKeyValue("request.method", request.method.name)
    recordKeyValue("request.proto", request.protocol.value)
    request.uri.query.toMultiMap.foreach {
      case (key, values) =>
        values.foreach(recordKeyValue("request.query." + key, _))
    }
    request.headers.foreach { header =>
      recordKeyValue("request.headers." + header.name, header.value)
    }
  }

  private[this] def traceServerSend[T](tracingId: Long)(implicit m: ToResponseMarshaller[T]): ToResponseMarshaller[T] =
    new ToResponseMarshaller[T] {
      override def apply(value: T, ctx: ToResponseMarshallingContext): Unit = {
        val result = value
        m.apply(result, new DelegatingToResponseMarshallingContext(ctx) {
          override def marshalTo(entity: HttpResponse): Unit = {
            super.marshalTo(entity)
            // TODO: use `finish` call after tracedComplete will be removed
            trace.addAnnotation(tracingId, TracingAnnotations.ServerSend.text)
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
  @deprecated("use tracedHandleWith directive instead", "0.5")
  def tracedComplete[T](rpc: String)(value: => T)(implicit m: ToResponseMarshaller[T]): StandardRoute =
    tracedComplete(self.path.name, rpc)(value)

}
