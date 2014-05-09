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

import akka.actor.Actor
import shapeless._
import spray.http.HttpResponse
import spray.httpx.marshalling._
import spray.httpx.unmarshalling._
import spray.routing._

import com.github.levkhomich.akka.tracing._

trait TracingDirectives { this: Actor with ActorTracing =>

  import spray.routing.directives.BasicDirectives._
  import spray.routing.directives.RouteDirectives._
  import spray.routing.directives.MiscDirectives._
  import TracingHeaders._

  private def tracedEntity[T <: TracingSupport](service: String)(implicit um: FromRequestUnmarshaller[T]): Directive[T :: BaseTracingSupport :: HNil] =
    hextract(ctx => ctx.request.as(um) :: extractSpan(ctx.request) :: HNil).hflatMap[T :: BaseTracingSupport :: HNil] {
      case Right(value) :: optSpan :: HNil =>
        optSpan.foreach(s => value.init(s.msgId, s.traceId.get, s.parentId))
        trace.sample(value, service)
        hprovide(value :: optSpan.getOrElse(value) :: HNil)
      case Left(ContentExpected) :: _ => reject(RequestEntityExpectedRejection)
      case Left(UnsupportedContentType(supported)) :: _ => reject(UnsupportedRequestContentTypeRejection(supported))
      case Left(MalformedContent(errorMsg, cause)) :: _ => reject(MalformedRequestContentRejection(errorMsg, cause))
    } & cancelAllRejections(ofTypes(RequestEntityExpectedRejection.getClass, classOf[UnsupportedRequestContentTypeRejection]))


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
    tracedEntity(service)(um) { case (a, span) =>
      intComplete(f(a))(traceServerSend(m, span))
    }

  /**
   * Completes the request using the given argument(s). Traces server receive and
   * send events, supports requests with tracing-specific headers. Shouldn't be used if
   * requests are also traced manually. Service name is set to HTTP service actor's name.
   *
   * @param rpc RPC name to be added to trace
   */
  def tracedComplete[T](rpc: String)(value: => T)(implicit marshaller: ToResponseMarshaller[T]): StandardRoute =
    tracedComplete(self.path.name, rpc)(value)

  /**
   * Completes the request using the given argument(s). Traces server receive and
   * send events, supports requests with tracing-specific headers. Shouldn't be used if
   * requests are also traced manually.
   *
   * @param service service name to be added to trace
   * @param rpc RPC name to be added to trace
   */
  def tracedComplete[T](service: String, rpc: String)(value: => T)(implicit marshaller: ToResponseMarshaller[T]): StandardRoute =
    new StandardRoute {
      def apply(ctx: RequestContext): Unit = {
        val span = extractSpan(ctx.request).getOrElse(Span.random)
        trace.sample(span, service, rpc)
        ctx.complete(value)(traceServerSend(marshaller, span))
      }
    }

  private def intComplete[T](result: => ToResponseMarshallable)(implicit m: ToResponseMarshaller[T]): Route =
    complete(result)

  private def traceServerSend[T](marshaller: ToResponseMarshaller[T], ts: BaseTracingSupport): ToResponseMarshaller[T] =
    new ToResponseMarshaller[T] {
      override def apply(value: T, ctx: ToResponseMarshallingContext): Unit = {
        val result = value
        marshaller.apply(result, new DelegatingToResponseMarshallingContext(ctx) {
          override def marshalTo(entity: HttpResponse): Unit = {
            super.marshalTo(entity)
            trace.recordServerSend(ts)
          }
        })
      }
    }

}

