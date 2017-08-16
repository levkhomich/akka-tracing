package com.github.levkhomich.akka.tracing.http

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ HttpEncodings, RawHeader, `Content-Encoding` }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RejectionHandler
import akka.http.scaladsl.unmarshalling.Unmarshaller
import com.github.levkhomich.akka.tracing._
import com.typesafe.config.Config
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, Promise }
import scala.util.Random

class TracingDirectivesSpec extends Specification with TracingTestCommons
    with BaseTracingDirectives with MockCollector with Specs2FrameworkInterface {

  sequential

  override def testConfig: Config = testConfig()

  "Akka HTTP tracedHandleWith directive" should {
    "sample requests" in {
      Get(testPath) ~> tracedHandleWithRoute ~> check {
        response.status mustEqual StatusCodes.OK
        val span = receiveSpan()
        success
      }
    }

    "send trace to server only after response future has completed" in {
      val traceKey = "traced-after-future-completed"
      val traceValue = "OK"
      val tracedHandledWithResponseFuture =
        handleRejections(RejectionHandler.default) {
          get {
            tracedHandleWith(serviceName) { r: TestMessage =>
              val computationPromise = Promise[HttpResponse]
              system.scheduler.scheduleOnce(delay = FiniteDuration(10, TimeUnit.MILLISECONDS)) {
                trace.recordKeyValue(r, traceKey, traceValue)
                computationPromise.success(HttpResponse(StatusCodes.OK))
              }
              computationPromise.future
            }
          }
        }

      Get(testPath) ~> tracedHandledWithResponseFuture ~> check {
        response.status mustEqual StatusCodes.OK
        val span = receiveSpan()
        checkBinaryAnnotation(span, traceKey, traceValue)
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
      Get(Uri.from(path        = testPath, queryString = Some("key=value"))).withHeaders(
        `Content-Encoding`(HttpEncodings.identity)
      ) ~> tracedHandleWithRoute ~> check {
          response.status mustEqual StatusCodes.OK
          val span = receiveSpan()
          checkBinaryAnnotation(span, "request.headers." + `Content-Encoding`.name, HttpEncodings.identity.toString)
          checkBinaryAnnotation(span, "request.query.key", "value")
        }
    }

    "propagate tracing headers" in {
      val spanId = Random.nextLong
      val parentId = Random.nextLong
      Get(testPath).withHeaders(
        RawHeader(TracingHeaders.TraceId, SpanMetadata.idToString(spanId)),
        RawHeader(TracingHeaders.ParentSpanId, SpanMetadata.idToString(parentId))
      ) ~> tracedHandleWithRoute ~> check {
          response.status mustEqual StatusCodes.OK
          val span = receiveSpan()
          checkBinaryAnnotation(span, "request.headers." + TracingHeaders.TraceId, SpanMetadata.idToString(spanId))
          checkBinaryAnnotation(span, "request.headers." + TracingHeaders.ParentSpanId, SpanMetadata.idToString(parentId))
        }
    }

    val MalformedHeaderRejection = "The value of HTTP header '%s' was malformed:\ninvalid value"
    "reject requests with malformed X-B3-TraceId header" in {
      Get(testPath).withHeaders(
        RawHeader(TracingHeaders.TraceId, "malformed")
      ) ~> tracedHandleWithRoute ~> check {
          response.status mustEqual StatusCodes.BadRequest
          responseAs[String] mustEqual (MalformedHeaderRejection format TracingHeaders.TraceId)
        }
    }

    "reject requests with malformed X-B3-ParentTraceId header" in {
      val spanId = Random.nextLong
      Get(testPath).withHeaders(
        RawHeader(TracingHeaders.TraceId, SpanMetadata.idToString(spanId)),
        RawHeader(TracingHeaders.ParentSpanId, "malformed")
      ) ~> tracedHandleWithRoute ~> check {
          response.status mustEqual StatusCodes.BadRequest
          responseAs[String] mustEqual (MalformedHeaderRejection format TracingHeaders.ParentSpanId)
        }
    }

    def testRejection(error: Exception, statusCode: StatusCode): MatchResult[_] = {
      implicit def um: Unmarshaller[HttpRequest, TestMessage] =
        Unmarshaller { ctx => request: HttpRequest =>
          Future.failed(error)
        }
      val route =
        handleRejections(RejectionHandler.default) {
          get {
            tracedHandleWith(serviceName) { r: TestMessage =>
              HttpResponse(StatusCodes.OK)
            }
          }
        }
      Get(testPath) ~> route ~> check {
        response.status mustEqual statusCode
        expectSpans(0)
      }
    }

    "not trace rejected requests (NoContentException)" in {
      testRejection(Unmarshaller.NoContentException, StatusCodes.BadRequest)
    }

    "not trace rejected requests (UnsupportedContentTypeException)" in {
      testRejection(Unmarshaller.UnsupportedContentTypeException(), StatusCodes.UnsupportedMediaType)
    }

    "not trace rejected requests (IllegalArgumentException)" in {
      testRejection(new IllegalArgumentException(""), StatusCodes.BadRequest)
    }

    "not trace rejected requests (other Exception)" in {
      testRejection(new NumberFormatException(""), StatusCodes.BadRequest)
    }

  }

  step {
    collector.stop()
    terminateActorSystem(system)
  }

}
