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
import scala.util.{ Failure, Random, Success, Try }

import akka.actor.Actor
import akka.http.scaladsl.marshalling.{ ToResponseMarshallable, ToResponseMarshaller }
import akka.http.scaladsl.model.{ HttpMessage, HttpRequest }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.{ FromRequestUnmarshaller, Unmarshaller }

import com.github.levkhomich.akka.tracing._

trait BaseTracingDirectives {

  protected def trace: TracingExtensionImpl

  import TracingDirectives._

  private[this] def tracedEntity[T <: TracingSupport](service: String)(implicit um: FromRequestUnmarshaller[T]): Directive1[T] =
    extractRequestContext.flatMap[Tuple1[T]] { ctx ⇒
      import ctx.executionContext
      import ctx.materializer
      onComplete(um(ctx.request)) flatMap {
        case Success(value) ⇒
          extractSpan(ctx.request, requireTraceId = false) match {
            case Right(maybeSpan) =>
              maybeSpan match {
                case Some(span) =>
                  trace.sample(value, span.spanId, span.parentId, span.traceId, service, span.forceSampling)
                  addHttpAnnotations(value.tracingId, ctx.request)
                case _ =>
              }
              provide(value)
            case Left(malformedHeaderName) =>
              reject(MalformedHeaderRejection(malformedHeaderName, "invalid value"))
          }

        case Failure(Unmarshaller.NoContentException) ⇒ reject(RequestEntityExpectedRejection)
        case Failure(Unmarshaller.UnsupportedContentTypeException(x)) ⇒ reject(UnsupportedRequestContentTypeRejection(x))
        case Failure(x: IllegalArgumentException) ⇒ reject(ValidationRejection(x.getMessage, Some(x)))
        case Failure(x) ⇒ reject(MalformedRequestContentRejection(x.getMessage, Option(x.getCause)))
      }
    } & cancelRejections(RequestEntityExpectedRejection.getClass, classOf[UnsupportedRequestContentTypeRejection])

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
    tracedEntity(service)(um).tapply {
      case Tuple1(ts) =>
        StandardRoute { ctx =>
          ctx.complete(ToResponseMarshallable(f(ts))(traceServerSend(ts.tracingId)))
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
    request.uri.query().toMultiMap.foreach {
      case (key, values) =>
        values.foreach(recordKeyValue("request.query." + key, _))
    }
    request.headers.foreach { header =>
      recordKeyValue("request.headers." + header.name, header.value)
    }
  }

  private[this] def traceServerSend[T](tracingId: Long)(implicit m: ToResponseMarshaller[T]): ToResponseMarshaller[T] =
    m.compose { v =>
      trace.record(tracingId, TracingAnnotations.ServerSend.text)
      v
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

}

private[http] object TracingDirectives {

  import TracingHeaders._

  def extractSpan(message: HttpMessage, requireTraceId: Boolean): Either[String, Option[SpanMetadata]] = {
    def headerStringValue(name: String): Option[String] =
      message.headers.find(_.name == name).map(_.value)
    def headerLongValue(name: String): Either[String, Option[Long]] =
      Try(headerStringValue(name).map(SpanMetadata.idFromString)) match {
        case Failure(e) =>
          Left(name)
        case Success(v) =>
          Right(v)
      }
    def spanId: Long =
      headerLongValue(SpanId).right.toOption.flatten.getOrElse(Random.nextLong)

    // debug flag forces sampling (see http://git.io/hdEVug)
    val maybeForceSampling =
      headerStringValue(Sampled).map(_.toLowerCase) match {
        case Some("0") | Some("false") =>
          Some(false)
        case Some("1") | Some("true") =>
          Some(true)
        case _ =>
          headerStringValue(Flags).flatMap(flags =>
            Try((java.lang.Long.parseLong(flags) & DebugFlag) == DebugFlag).toOption.filter(v => v))
      }

    maybeForceSampling match {
      case Some(false) =>
        Right(None)
      case _ =>
        val forceSampling = maybeForceSampling.getOrElse(false)
        headerLongValue(TraceId).right.map({
          case Some(traceId) =>
            headerLongValue(ParentSpanId).right.map { parentId =>
              Some(SpanMetadata(traceId, spanId, parentId, forceSampling))
            }
          case _ if requireTraceId =>
            Right(None)
          case _ =>
            Right(Some(SpanMetadata(Random.nextLong, spanId, None, forceSampling)))
        }).joinRight
    }
  }

}

