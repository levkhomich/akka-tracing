package com.github.levkhomich.akka.tracing.http

import java.util.concurrent.TimeoutException
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.util.Random

import org.specs2.matcher.MatchResult
import spray.http._
import spray.httpx.unmarshalling.{ Deserialized, FromRequestUnmarshaller }
import spray.routing.HttpService
import spray.testkit.Specs2RouteTest

import com.github.levkhomich.akka.tracing._

class TracingDirectivesSpec extends AkkaTracingSpecification with BaseTracingDirectives
    with MockCollector with Specs2RouteTest with HttpService {

  sequential

  override implicit val system = testActorSystem()
  override val actorRefFactory = system
  val serviceName = "testService"
  val rpcName = "testRpc"
  val testPath = "/test-path"

  override protected def trace: TracingExtensionImpl =
    TracingExtension(system)

  val tracedHandleWithRoute =
    get {
      tracedHandleWith(serviceName) { r: TestRequest =>
        HttpResponse(StatusCodes.OK)
      }
    }

  val tracedCompleteRoute =
    get {
      tracedComplete(serviceName, rpcName)(HttpResponse(StatusCodes.OK))
    }

  "tracedHandleWith directive" should {
    "sample requests" in {
      Get(testPath) ~> tracedHandleWithRoute ~> check {
        response.status mustEqual StatusCodes.OK
        val span = receiveSpan()
        checkBinaryAnnotation(span, "request.path", testPath)
        checkBinaryAnnotation(span, "request.uri", "http://example.com/test-path")
        checkBinaryAnnotation(span, "request.method", "GET")
        checkBinaryAnnotation(span, "request.proto", "HTTP/1.1")
      }
    }

    "annotate sampled requests (general)" in {
      Get(testPath) ~> tracedHandleWithRoute ~> check {
        response.status mustEqual StatusCodes.OK
        val span = receiveSpan()
        checkBinaryAnnotation(span, "request.path", testPath)
        checkBinaryAnnotation(span, "request.uri", "http://example.com/test-path")
        checkBinaryAnnotation(span, "request.method", "GET")
        checkBinaryAnnotation(span, "request.proto", "HTTP/1.1")
      }
    }

    "annotate sampled requests (query params, headers)" in {
      Get(Uri.from(path = testPath, query = Uri.Query("key" -> "value"))).withHeaders(
        HttpHeaders.`Content-Type`(ContentTypes.`text/plain`) ::
          Nil
      ) ~> tracedHandleWithRoute ~> check {
          response.status mustEqual StatusCodes.OK
          val span = receiveSpan()
          checkBinaryAnnotation(span, "request.headers." + HttpHeaders.`Content-Type`.name, ContentTypes.`text/plain`.toString)
          checkBinaryAnnotation(span, "request.query.key", "value")
        }
    }

    val spanId = Random.nextLong
    val parentId = Random.nextLong
    "propagate tracing headers" in {
      Get(testPath).withHeaders(
        HttpHeaders.RawHeader(TracingHeaders.TraceId, Span.asString(spanId)) ::
          HttpHeaders.RawHeader(TracingHeaders.ParentSpanId, Span.asString(parentId)) ::
          Nil
      ) ~> tracedHandleWithRoute ~> check {
          response.status mustEqual StatusCodes.OK
          val span = receiveSpan()
          checkBinaryAnnotation(span, "request.headers." + TracingHeaders.TraceId, Span.asString(spanId))
          checkBinaryAnnotation(span, "request.headers." + TracingHeaders.ParentSpanId, Span.asString(parentId))
        }
    }
  }

  "tracedComplete directive" should {
    "not sample requests without tracing headers" in {
      Get(testPath) ~> tracedCompleteRoute ~> check {
        response.status mustEqual StatusCodes.OK
        Thread.sleep(3000)
        results.size mustEqual 0
      }
    }

    "sample requests with tracing headers" in {
      val spanId = Random.nextLong
      Get(testPath).withHeaders(
        HttpHeaders.RawHeader(TracingHeaders.TraceId, Span.asString(spanId)) ::
          Nil
      ) ~> tracedCompleteRoute ~> check {
          response.status mustEqual StatusCodes.OK
          val span = receiveSpan()
          span.get_trace_id mustEqual spanId
          span.get_name mustEqual rpcName
          span.get_annotations.head.get_host.get_service_name mustEqual serviceName
        }
    }

    "annotate sampled requests (general)" in {
      val spanId = Random.nextLong
      Get(testPath).withHeaders(
        HttpHeaders.RawHeader(TracingHeaders.TraceId, Span.asString(spanId)) ::
          Nil
      ) ~> tracedCompleteRoute ~> check {
          response.status mustEqual StatusCodes.OK
          val span = receiveSpan()
          checkBinaryAnnotation(span, "request.path", testPath)
          checkBinaryAnnotation(span, "request.uri", "http://example.com/test-path")
          checkBinaryAnnotation(span, "request.method", "GET")
          checkBinaryAnnotation(span, "request.proto", "HTTP/1.1")
        }
    }

    "annotate sampled requests (query params, headers)" in {
      val spanId = Random.nextLong
      Get(Uri.from(path = testPath, query = Uri.Query("key" -> "value"))).withHeaders(
        HttpHeaders.RawHeader(TracingHeaders.TraceId, Span.asString(spanId)) ::
          HttpHeaders.`Content-Type`(ContentTypes.`text/plain`) ::
          Nil
      ) ~> tracedCompleteRoute ~> check {
          response.status mustEqual StatusCodes.OK
          val span = receiveSpan()
          checkBinaryAnnotation(span, "request.headers." + HttpHeaders.`Content-Type`.name, ContentTypes.`text/plain`.toString)
          checkBinaryAnnotation(span, "request.query.key", "value")
        }
    }

    "propagate tracing headers" in {
      val spanId = Random.nextLong
      val parentId = Random.nextLong
      Get(testPath).withHeaders(
        HttpHeaders.RawHeader(TracingHeaders.TraceId, Span.asString(spanId)) ::
          HttpHeaders.RawHeader(TracingHeaders.ParentSpanId, Span.asString(parentId)) ::
          Nil
      ) ~> tracedCompleteRoute ~> check {
          response.status mustEqual StatusCodes.OK
          val span = receiveSpan()
          checkBinaryAnnotation(span, "request.headers." + TracingHeaders.TraceId, Span.asString(spanId))
          checkBinaryAnnotation(span, "request.headers." + TracingHeaders.ParentSpanId, Span.asString(parentId))
        }
    }
  }

  "shutdown correctly" in {
    system.shutdown()
    collector.stop()
    system.awaitTermination(FiniteDuration(5, SECONDS)) must not(throwA[TimeoutException])
  }

  private[this] def checkBinaryAnnotation(span: thrift.Span, key: String, expValue: String): MatchResult[Any] = {
    span.binary_annotations.find(_.get_key == key) match {
      case Some(ba) =>
        val actualValue = new String(ba.get_value, "UTF-8")
        actualValue mustEqual expValue
      case _ =>
        ko(key + " = " + expValue + " not found")
    }
  }

  final case class TestRequest(text: String) extends TracingSupport

  private[this] def receiveSpan(): thrift.Span = {
    Thread.sleep(3000)
    val spans = results.map(e => decodeSpan(e.message))
    spans.size mustEqual 1
    results.clear()
    spans.head
  }

  object TestRequest {
    implicit def um: FromRequestUnmarshaller[TestRequest] =
      new FromRequestUnmarshaller[TestRequest] {
        override def apply(request: HttpRequest): Deserialized[TestRequest] =
          Right(TestRequest(request.entity.asString))
      }
  }

}
