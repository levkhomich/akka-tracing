package com.github.levkhomich.akka.tracing.http

import akka.http.scaladsl.model.{ StatusCodes, HttpResponse, HttpRequest }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RejectionHandler
import akka.http.scaladsl.testkit.{ RouteTest, TestFrameworkInterface }
import akka.http.scaladsl.unmarshalling._
import com.github.levkhomich.akka.tracing.{ TracingTestCommons, TracingExtension, TracingExtensionImpl, TestMessage }

trait Specs2FrameworkInterface extends RouteTest with TestFrameworkInterface { this: TracingTestCommons with BaseTracingDirectives =>

  val serviceName = "testService"
  val testPath = "/test-path"

  override protected def trace: TracingExtensionImpl =
    TracingExtension(system)

  val tracedHandleWithRoute =
    handleRejections(RejectionHandler.default) {
      get {
        tracedHandleWith(serviceName) { r: TestMessage =>
          HttpResponse(StatusCodes.OK)
        }
      }
    }

  def failTest(msg: String): Nothing = {
    throw new IllegalStateException(msg)
  }

  implicit def um: FromRequestUnmarshaller[TestMessage] =
    Unmarshaller { implicit ctx =>
      request: HttpRequest =>
        import scala.concurrent.duration._
        request.entity.toStrict(FiniteDuration(100, MILLISECONDS)).map { v =>
          TestMessage(v.toString)
        }
    }

}
