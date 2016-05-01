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

import scala.concurrent.{ Await, Future }
import scala.util.Random
import scala.collection.immutable.Set
import play.api.{ GlobalSettings, Play }
import play.api.http.Writeable
import play.api.libs.iteratee.Enumerator
import play.api.mvc._
import play.api.test._
import org.specs2.matcher._

import com.github.levkhomich.akka.tracing._
import com.github.levkhomich.akka.tracing.http.TracingHeaders
import scala.collection.JavaConversions._

class PlayTracingSpec extends PlaySpecification with TracingTestCommons with MockCollector with Results with ResultMatchers {

  sequential

  val TestPath = "/request"
  val TestErrorPath = "/error"
  val npe = new NullPointerException
  implicit def trace: TracingExtensionImpl = TracingExtension(_root_.play.libs.Akka.system)

  val configuration = Map(
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
    additionalConfiguration = configuration ++ Map(TracingExtension.AkkaTracingSampleRate -> 0)
  )

  "Play tracing" should {
    "sample requests" in new WithApplication(fakeApplication) {
      val result = route(FakeRequest("GET", TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      success
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

    "propagate tracing headers" in new WithApplication(fakeApplication) {
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

    val Sampled = Seq("1", "true")
    val NotSampled = Seq("0", "false")

    (Sampled ++ NotSampled).foreach { value =>
      s"honour upstream's Sampled: $value header" in new WithApplication(fakeApplication) {
        val spanId = Random.nextLong
        val result = route(FakeRequest("GET", TestPath + "?key=value",
          FakeHeaders(Seq(
            TracingHeaders.TraceId -> Seq(SpanMetadata.idToString(spanId)),
            TracingHeaders.Sampled -> Seq(value)
          )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
        expectSpans(if (Sampled.contains(value)) 1 else 0)
      }
    }

    "allow forced sampling" in new WithApplication(disabledLocalSamplingApplication) {
      val result = route(FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.Flags -> Seq("1")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      receiveSpans() must haveLength(1)
    }

    "ignore broken Flags header" in new WithApplication(disabledLocalSamplingApplication) {
      val result = route(FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.Flags -> Seq("broken")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      receiveSpans() must beEmpty
    }

    "ignore broken TraceId header" in new WithApplication(fakeApplication) {
      val result = route(FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> Seq("broken")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      receiveSpans() must haveLength(1)
    }

    "ignore broken SpanId header" in new WithApplication(fakeApplication) {
      val traceId = Random.nextLong

      val result = route(FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> Seq(SpanMetadata.idToString(traceId)),
          TracingHeaders.SpanId -> Seq("broken")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      val span = receiveSpan()
      span.get_trace_id mustEqual traceId
    }

    "ignore broken ParentSpanId header" in new WithApplication(fakeApplication) {
      val traceId = Random.nextLong

      val result = route(FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> Seq(SpanMetadata.idToString(traceId)),
          TracingHeaders.ParentSpanId -> Seq("broken")
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      val span = receiveSpan()
      span.get_trace_id mustEqual traceId
    }

    "ignore broken Sampled header" in new WithApplication(fakeApplication) {
      val spanId = Random.nextLong
      val result = route(FakeRequest("GET", TestPath + "?key=value",
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> Seq(SpanMetadata.idToString(spanId)),
          TracingHeaders.Sampled -> Seq("unexpected value")
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
