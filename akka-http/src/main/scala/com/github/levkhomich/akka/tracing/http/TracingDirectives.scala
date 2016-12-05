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

import akka.actor.Actor
import akka.http.scaladsl.marshalling.{ ToResponseMarshallable, ToResponseMarshaller }
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller

import com.github.levkhomich.akka.tracing._

import scala.concurrent.ExecutionContext

trait BaseTracingDirectives {

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
  def tracedHandleWith[A <: TracingSupport, B](service: String)(f: A => B)
                                              (implicit um: FromRequestUnmarshaller[A],
                                               m: ToResponseMarshaller[B],
                                               ec: ExecutionContext): Route = {
    tracedEntity(service)(um).tapply {
      case Tuple1(ts) =>
        StandardRoute { ctx =>
          val completeFut = ctx.complete(ToResponseMarshallable(f(ts)))
          completeFut onComplete {
            _ => trace.record(ts, TracingAnnotations.ServerSend.text)
          }
          completeFut
        }
    }
  }

  protected def trace: TracingExtensionImpl

  private[this] def tracedEntity[T <: TracingSupport](service: String)(implicit um: FromRequestUnmarshaller[T]): Directive1[T] =
    (entity(um) & extractRequest).tflatMap {
      case (value, request) =>
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

  private[this] def addHttpAnnotations(tracingId: Long, request: HttpRequest): Unit = {
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
  def tracedHandleWith[A <: TracingSupport, B](f: A => B)(implicit um: FromRequestUnmarshaller[A],
                                                          m: ToResponseMarshaller[B],
                                                          executionContext: ExecutionContext): Route =
    tracedHandleWith(self.path.name)(f)

}
