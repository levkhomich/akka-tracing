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

import javax.inject.Inject
import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.util.Random

import akka.actor.ActorSystem
import org.specs2.matcher._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.test._

import com.github.levkhomich.akka.tracing._
import com.github.levkhomich.akka.tracing.http.TracingHeaders

class PlayTracingSpec extends PlaySpecification with TracingTestCommons with MockCollector with Results with ResultMatchers {

  sequential

  implicit def trace(@Inject system: ActorSystem): TracingExtensionImpl = TracingExtension(system)

  val configuration: Map[String, Any] = Map(
    TracingExtension.AkkaTracingHost -> DefaultTracingHost,
    TracingExtension.AkkaTracingPort -> collectorPort
  )
  val TestPath = "/request"
  val routes: PartialFunction[(String, String), Handler] = {
    case ("GET", TestPath) =>
      Action(Ok("response") as "text/plain")
  }

  def fakeApplication = GuiceApplicationBuilder().routes(routes).configure(configuration).build

  def disabledLocalSamplingApplication = GuiceApplicationBuilder().routes(routes).
    configure(configuration ++ Map(TracingExtension.AkkaTracingSampleRate -> Int.MaxValue)).build

  "Play tracing" should {
    "sample requests" in new WithApplication(fakeApplication) {
      val result = route(app, FakeRequest("GET", TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      expectSpans(1)
    }

    "use play application name as the default end point name" in new WithApplication(fakeApplication) {
      val result = route(app, FakeRequest("GET", TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      private val expName = "application" // TODO: find a constant for it
      span.annotations.map(_.get_host().get_service_name()) must beEqualTo(Seq(expName, expName))
    }

    "annotate sampled requests (general)" in new WithApplication(fakeApplication) {
      val result = route(app, FakeRequest("GET", TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkBinaryAnnotation(span, "request.path", TestPath)
      checkBinaryAnnotation(span, "request.method", "GET")
      checkBinaryAnnotation(span, "request.secure", false)
      checkBinaryAnnotation(span, "request.proto", "HTTP/1.1")
    }

    "annotate sampled requests (query params, headers)" in new WithApplication(fakeApplication) {
      val result = route(app, FakeRequest("GET", TestPath + "?key=value",
        FakeHeaders(Seq("Content-Type" -> "text/plain")), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkBinaryAnnotation(span, "request.headers.Content-Type", "text/plain")
      checkBinaryAnnotation(span, "request.query.key", "value")
    }

    "support trace propagation from external service" in new WithApplication(fakeApplication) {
      val traceId = Random.nextLong
      val parentId = Random.nextLong

      val result = route(app, FakeRequest("GET", TestPath + "?key=value",
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> SpanMetadata.idToString(traceId),
          TracingHeaders.ParentSpanId -> SpanMetadata.idToString(parentId)
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      val span = receiveSpan()
      span.get_parent_id mustEqual parentId
      span.get_trace_id mustEqual traceId
    }

    Seq("1", "true").foreach { value =>
      s"honour upstream's X-B3-Sampled: $value header" in new WithApplication(disabledLocalSamplingApplication) {
        val spanId = Random.nextLong
        val result = route(app, FakeRequest("GET", TestPath + "?key=value",
          FakeHeaders(Seq(
            TracingHeaders.TraceId -> SpanMetadata.idToString(spanId),
            TracingHeaders.Sampled -> value
          )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
        expectSpans(1)
      }
    }

    Seq("0", "false").foreach { value =>
      s"honour upstream's X-B3-Sampled: $value header" in new WithApplication(fakeApplication) {
        val spanId = Random.nextLong
        val result = route(app, FakeRequest("GET", TestPath + "?key=value",
          FakeHeaders(Seq(
            TracingHeaders.TraceId -> SpanMetadata.idToString(spanId),
            TracingHeaders.Sampled -> value
          )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
        expectSpans(0)
      }
    }

    Seq("1", "true").foreach { value =>
      s"honour upstream's X-B3-Sampled: $value header if X-B3-TraceId is not specified" in new WithApplication(disabledLocalSamplingApplication) {
        val spanId = Random.nextLong
        val result = route(app, FakeRequest("GET", TestPath + "?key=value",
          FakeHeaders(Seq(
            TracingHeaders.Sampled -> value
          )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
        expectSpans(1)
      }
    }

    "honour upstream's Debug flag" in new WithApplication(disabledLocalSamplingApplication) {
      val result = route(app, FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.Flags -> "1"
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      expectSpans(1)
    }

    "user regular sampling if X-B3-Flags does not contain Debug flag" in new WithApplication(disabledLocalSamplingApplication) {
      val result = route(app, FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.Flags -> "2"
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      expectSpans(0)
    }

    "ignore malformed X-B3-Flags header" in new WithApplication(disabledLocalSamplingApplication) {
      val result = route(app, FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.Flags -> "malformed"
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      expectSpans(0)
    }

    "ignore malformed X-B3-TraceId header" in new WithApplication(fakeApplication) {
      val result = route(app, FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> "malformed"
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      expectSpans(1)
    }

    "ignore malformed X-B3-SpanId header" in new WithApplication(fakeApplication) {
      val traceId = Random.nextLong

      val result = route(app, FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> SpanMetadata.idToString(traceId),
          TracingHeaders.SpanId -> "malformed"
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      val span = receiveSpan()
      span.get_trace_id mustEqual traceId
    }

    "ignore malformed X-B3-ParentSpanId header" in new WithApplication(fakeApplication) {
      val traceId = Random.nextLong

      val result = route(app, FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> SpanMetadata.idToString(traceId),
          TracingHeaders.ParentSpanId -> "malformed"
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      val span = receiveSpan()
      // should create new trace instead of using broken one
      span.get_trace_id mustNotEqual traceId
    }

    "ignore malformed X-B3-Sampled header" in new WithApplication(fakeApplication) {
      val spanId = Random.nextLong
      val result = route(app, FakeRequest("GET", TestPath + "?key=value",
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> SpanMetadata.idToString(spanId),
          TracingHeaders.Sampled -> "malformed"
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
      expectSpans(1)
    }
  }

  step {
    collector.stop()
  }
}
