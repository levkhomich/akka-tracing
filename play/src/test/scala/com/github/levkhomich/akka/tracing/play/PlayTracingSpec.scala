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

package com.github.levkhomich.akka.tracing.play

import scala.collection.JavaConversions._
import scala.collection.immutable.Set
import scala.concurrent.{ Await, Future }
import scala.util.Random

import play.api.{ GlobalSettings, Play }
import play.api.http.Writeable
import play.api.libs.iteratee.Enumerator
import play.api.mvc._
import play.api.test._
import org.specs2.matcher._

import com.github.levkhomich.akka.tracing._
import com.github.levkhomich.akka.tracing.http.TracingHeaders

class PlayTracingSpec extends PlaySpecification with TracingTestCommons with MockCollector with Results with ResultMatchers {

  sequential

  val TestPath = "/request"
  val TestErrorPath = "/error"
  val npe = new NullPointerException
  implicit def trace: TracingExtensionImpl = TracingExtension(_root_.play.libs.Akka.system)

  val configuration = Map(
    TracingExtension.AkkaTracingHost -> DefaultTracingHost,
    TracingExtension.AkkaTracingPort -> collectorPort
  )
  val routes: PartialFunction[(String, String), Handler] = {
    case ("GET", TestPath) =>
      Action {
        Ok("response") as "text/plain"
      }
    case ("GET", TestErrorPath) =>
      Action {
        throw npe
        Ok("response") as "text/plain"
      }
  }

  def fakeApplication: FakeApplication = FakeApplication(
    withRoutes = routes,
    withGlobal = Some(new GlobalSettings with TracingSettings),
    additionalConfiguration = configuration
  )

  def overriddenApplication(overriddenServiceName: String = "test", queryParams: Set[String] = Set.empty, headerKeys: Set[String] = Set.empty) = FakeApplication(
    withRoutes = routes,
    withGlobal = Some(new GlobalSettings with TracingSettings {
      override lazy val serviceName = overriddenServiceName
      override lazy val excludedQueryParams = queryParams
      override lazy val excludedHeaders = headerKeys
    }),
    additionalConfiguration = configuration
  )

  def disabledLocalSamplingApplication: FakeApplication = FakeApplication(
    withRoutes = routes,
    withGlobal = Some(new GlobalSettings with TracingSettings),
    additionalConfiguration = configuration ++ Map(TracingExtension.AkkaTracingSampleRate -> Int.MaxValue)
  )

  "Play tracing" should {
    "sample requests" in new WithApplication(fakeApplication) {
      val result = route(FakeRequest("GET", TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      expectSpans(1)
    }

    "use play application name as the default end point name" in new WithApplication(fakeApplication) {
      val result = route(FakeRequest("GET", TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      span.annotations.map(_.get_host().get_service_name()) must beEqualTo(_root_.play.libs.Akka.system.name).forall
    }

    "enable overriding the service name for the end point name" in new WithApplication(overriddenApplication(overriddenServiceName = "test service")) {
      val result = route(FakeRequest("GET", TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      span.annotations.map(_.get_host().get_service_name()) must beEqualTo("test service").forall
    }

    "not allow to use RequestHeaders as child of other request" in new WithApplication(fakeApplication) {
      val parent = new TracingSupport {}
      val request = FakeRequest("GET", TestPath)
      new PlayControllerTracing {
        request.asChildOf(parent)
      } must throwA[IllegalStateException]
    }

    "annotate sampled requests (general)" in new WithApplication(fakeApplication) {
      val result = route(FakeRequest("GET", TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkBinaryAnnotation(span, "request.path", TestPath)
      checkBinaryAnnotation(span, "request.method", "GET")
      checkBinaryAnnotation(span, "request.secure", false)
      checkBinaryAnnotation(span, "request.proto", "HTTP/1.1")
    }

    "annotate sampled requests (query params, headers)" in new WithApplication(fakeApplication) {
      val result = route(FakeRequest("GET", TestPath + "?key=value",
        FakeHeaders(Seq("Content-Type" -> Seq("text/plain"))), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkBinaryAnnotation(span, "request.headers.Content-Type", "text/plain")
      checkBinaryAnnotation(span, "request.query.key", "value")
    }

    "exclude specific query values from annotations when configured" in new WithApplication(overriddenApplication(queryParams = Set("excludedParam"))) {
      val result = route(FakeRequest("GET", TestPath + "?key=value&excludedParam=value",
        FakeHeaders(Seq("Content-Type" -> Seq("text/plain"))), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkBinaryAnnotation(span, "request.query.key", "value")
      checkAbsentBinaryAnnotation(span, "request.query.excludedParam")
    }

    "exclude specific header fields from annotations when configured" in new WithApplication(overriddenApplication(headerKeys = Set("Excluded"))) {
      val result = route(FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          "Content-Type" -> Seq("text/plain"),
          "Excluded" -> Seq("test"),
          "Included" -> Seq("value")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkBinaryAnnotation(span, "request.headers.Included", "value")
      checkAbsentBinaryAnnotation(span, "request.headers.Excluded")
    }

    "support trace propagation from external service" in new WithApplication(fakeApplication) {
      val traceId = Random.nextLong
      val parentId = Random.nextLong

      val result = route(FakeRequest("GET", TestPath + "?key=value",
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> Seq(SpanMetadata.idToString(traceId)),
          TracingHeaders.ParentSpanId -> Seq(SpanMetadata.idToString(parentId))
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      val span = receiveSpan()
      span.get_parent_id mustEqual parentId
      span.get_trace_id mustEqual traceId
    }

    "record server errors to traces" in new WithApplication(fakeApplication) {
      val result = route(FakeRequest("GET", TestErrorPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkAnnotation(span, TracingExtension.getStackTrace(npe))
    }

    Seq("1", "true").foreach { value =>
      s"honour upstream's X-B3-Sampled: $value header" in new WithApplication(disabledLocalSamplingApplication) {
        val spanId = Random.nextLong
        val result = route(FakeRequest("GET", TestPath + "?key=value",
          FakeHeaders(Seq(
            TracingHeaders.TraceId -> Seq(SpanMetadata.idToString(spanId)),
            TracingHeaders.Sampled -> Seq(value)
          )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
        expectSpans(1)
      }
    }

    Seq("0", "false").foreach { value =>
      s"honour upstream's X-B3-Sampled: $value header" in new WithApplication(fakeApplication) {
        val spanId = Random.nextLong
        val result = route(FakeRequest("GET", TestPath + "?key=value",
          FakeHeaders(Seq(
            TracingHeaders.TraceId -> Seq(SpanMetadata.idToString(spanId)),
            TracingHeaders.Sampled -> Seq(value)
          )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
        expectSpans(0)
      }
    }

    Seq("1", "true").foreach { value =>
      s"honour upstream's X-B3-Sampled: $value header if X-B3-TraceId is not specified" in new WithApplication(disabledLocalSamplingApplication) {
        val spanId = Random.nextLong
        val result = route(FakeRequest("GET", TestPath + "?key=value",
          FakeHeaders(Seq(
            TracingHeaders.Sampled -> Seq(value)
          )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
        expectSpans(1)
      }
    }

    "honour upstream's Debug flag" in new WithApplication(disabledLocalSamplingApplication) {
      val result = route(FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.Flags -> Seq("1")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      expectSpans(1)
    }

    "user regular sampling if X-B3-Flags does not contain Debug flag" in new WithApplication(disabledLocalSamplingApplication) {
      val result = route(FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.Flags -> Seq("2")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      expectSpans(0)
    }

    "ignore malformed X-B3-Flags header" in new WithApplication(disabledLocalSamplingApplication) {
      val result = route(FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.Flags -> Seq("malformed")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      expectSpans(0)
    }

    "ignore malformed X-B3-TraceId header" in new WithApplication(fakeApplication) {
      val result = route(FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> Seq("malformed")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      expectSpans(1)
    }

    "ignore malformed X-B3-SpanId header" in new WithApplication(fakeApplication) {
      val traceId = Random.nextLong

      val result = route(FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> Seq(SpanMetadata.idToString(traceId)),
          TracingHeaders.SpanId -> Seq("malformed")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      val span = receiveSpan()
      span.get_trace_id mustEqual traceId
    }

    "ignore malformed X-B3-ParentSpanId header" in new WithApplication(fakeApplication) {
      val traceId = Random.nextLong

      val result = route(FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> Seq(SpanMetadata.idToString(traceId)),
          TracingHeaders.ParentSpanId -> Seq("malformed")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      val span = receiveSpan()
      // should create new trace instead of using broken one
      span.get_trace_id mustNotEqual traceId
    }

    "ignore malformed X-B3-Sampled header" in new WithApplication(fakeApplication) {
      val spanId = Random.nextLong
      val result = route(FakeRequest("GET", TestPath + "?key=value",
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> Seq(SpanMetadata.idToString(spanId)),
          TracingHeaders.Sampled -> Seq("malformed")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
      expectSpans(1)
    }
  }

  step {
    collector.stop()
  }

  // it seems that play-test doesn't call global.onRequestCompletion and global.onError
  override def call[T](action: EssentialAction, rh: RequestHeader, body: T)(implicit w: Writeable[T]): Future[Result] = {
    val rhWithCt = w.contentType.map(ct => rh.copy(
      headers = FakeHeaders((rh.headers.toMap + ("Content-Type" -> Seq(ct))).toSeq)
    )).getOrElse(rh)
    val requestBody = Enumerator(body) &> w.toEnumeratee
    val result = requestBody |>>> action(rhWithCt).recover {
      case e =>
        Play.current.global.onError(rh, e)
        InternalServerError
    }
    result.onComplete {
      case _ =>
        Play.current.global.onRequestCompletion(rh)
    }
    result
  }

}
