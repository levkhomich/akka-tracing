package com.github.levkhomich.akka.tracing.http

import java.util.concurrent.TimeoutException

import scala.collection.JavaConversions._

import org.specs2.matcher.MatchResult
import spray.http.{ HttpRequest, StatusCodes, HttpResponse }
import spray.httpx.unmarshalling.{ Deserialized, FromRequestUnmarshaller }
import spray.routing.HttpService
import spray.testkit.Specs2RouteTest

import com.github.levkhomich.akka.tracing._

import scala.concurrent.duration._

class TracingDirectivesSpec extends AkkaTracingSpecification with BaseTracingDirectives
    with MockCollector with Specs2RouteTest with HttpService {

  sequential

  override implicit val system = testActorSystem()
  def actorRefFactory = system
  val serviceName = "testService"

  final case class TestRequest(text: String) extends TracingSupport

  object TestRequest {
    implicit def um: FromRequestUnmarshaller[TestRequest] =
      new FromRequestUnmarshaller[TestRequest] {
        override def apply(request: HttpRequest): Deserialized[TestRequest] =
          Right(TestRequest(request.entity.asString))
      }
  }

  override protected def trace: TracingExtensionImpl =
    TracingExtension(system)

  val tracedHandleWithRoute =
    get {
      tracedHandleWith(serviceName) { r: TestRequest =>
        HttpResponse(StatusCodes.OK)
      }
    }

  "tracedHandleWith" should {

    "sample requests and annotate them using HttpRequest data" in {
      Get("/test-path") ~> tracedHandleWithRoute ~> check {
        response.status mustEqual StatusCodes.OK
        Thread.sleep(3000)

        val spans = results.map(e => decodeSpan(e.message))
        spans.size mustEqual 1

        val span = spans.head

        def checkBinaryAnnotation(key: String, expValue: String): MatchResult[Any] = {
          val ba = span.binary_annotations.find(_.get_key == key)
          ba.isDefined mustEqual true
          val actualValue = new String(ba.get.get_value, "UTF-8")
          actualValue mustEqual expValue
        }

        checkBinaryAnnotation("request.path", "/test-path")
        checkBinaryAnnotation("request.uri", "http://example.com/test-path")
        checkBinaryAnnotation("request.method", "GET")
        checkBinaryAnnotation("request.proto", "HTTP/1.1")
      }
    }

    "shutdown correctly" in {
      system.shutdown()
      collector.stop()
      system.awaitTermination(FiniteDuration(5, SECONDS)) must not(throwA[TimeoutException])
    }
  }
}
