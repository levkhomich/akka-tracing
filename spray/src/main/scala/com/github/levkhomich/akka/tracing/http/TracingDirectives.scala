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

import com.github.levkhomich.akka.tracing.{TracingExtensionImpl, BaseTracingSupport, TracingSupport, ActorTracing}

private[http] final case class Span(traceId: Option[Long], spanId: Long, parentId: Option[Long]) extends BaseTracingSupport {
  override private[tracing] def setTraceId(newTraceId: Option[Long]): Unit = throw new UnsupportedOperationException
  override def asChildOf(ts: BaseTracingSupport)(implicit tracer: TracingExtensionImpl): this.type = this
  override private[tracing] def msgId: Long = spanId
}

trait TracingDirectives { this: Actor with ActorTracing =>

  import spray.routing.directives.BasicDirectives._
  import spray.routing.directives.RouteDirectives._
  import spray.routing.directives.MiscDirectives._
  import TracingHeaders._

  private def tracedEntity[T <: TracingSupport](service: String)(implicit um: FromRequestUnmarshaller[T]): Directive[T :: Option[Span] :: HNil] =
    hextract(ctx => ctx.request.as(um) :: extractSpan(ctx.request) :: HNil).hflatMap[T :: Option[Span] :: HNil] {
      case Right(value) :: optSpan :: HNil =>
        optSpan.foreach(s => value.init(s.spanId, s.traceId.get, s.parentId))
        trace.sample(value, service)
        hprovide(value :: optSpan :: HNil)
      case Left(ContentExpected) :: _ => reject(RequestEntityExpectedRejection)
      case Left(UnsupportedContentType(supported)) :: _ => reject(UnsupportedRequestContentTypeRejection(supported))
      case Left(MalformedContent(errorMsg, cause)) :: _ => reject(MalformedRequestContentRejection(errorMsg, cause))
    } & cancelAllRejections(ofTypes(RequestEntityExpectedRejection.getClass, classOf[UnsupportedRequestContentTypeRejection]))


  def tracedHandleWith[A <: TracingSupport, B](f: A => B)(implicit um: FromRequestUnmarshaller[A], m: ToResponseMarshaller[B]): Route =
    tracedHandleWith(self.path.name)(f)

  def tracedHandleWith[A <: TracingSupport, B](service: String)(f: A => B)(implicit um: FromRequestUnmarshaller[A], m: ToResponseMarshaller[B]): Route =
    tracedEntity(service)(um) { case (a, optTrace) =>
      optTrace match {
        case Some(span) =>
          intComplete(f(a))(traceServerSend(m, span))
        case None =>
          complete(f(a))
      }
    }

  def tracedComplete[T](rpc: String)(value: => T)(implicit marshaller: ToResponseMarshaller[T]): StandardRoute =
    tracedComplete(self.path.name, rpc)(value)

  def tracedComplete[T](service: String, rpc: String)(value: => T)(implicit marshaller: ToResponseMarshaller[T]): StandardRoute =
    new StandardRoute {
      def apply(ctx: RequestContext): Unit =
        extractSpan(ctx.request) match {
          case Some(span) =>
            trace.sample(span, service, rpc)
            ctx.complete(value)(traceServerSend(marshaller, span))

          case None =>
            ctx.complete(value)
        }
    }

  private def intComplete[T](result: => ToResponseMarshallable)(implicit m: ToResponseMarshaller[T]): Route =
    complete(result)

  private def traceServerSend[T](marshaller: ToResponseMarshaller[T], span: Span): ToResponseMarshaller[T] =
    new ToResponseMarshaller[T] {
      override def apply(value: T, ctx: ToResponseMarshallingContext): Unit = {
        val result = value
        marshaller.apply(result, new DelegatingToResponseMarshallingContext(ctx) {
          override def marshalTo(entity: HttpResponse): Unit = {
            super.marshalTo(entity)
            trace.recordServerSend(span)
          }
        })
      }
    }

}

