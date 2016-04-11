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

import org.specs2.matcher._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.routing._
import play.api.test._
import play.api.test.Helpers.{ GET => GET_REQUEST, _ }
import com.github.levkhomich.akka.tracing.{ MockCollector, TracingExtension, TracingExtensionImpl, TracingTestCommons }
import com.github.levkhomich.akka.tracing.{ SpanMetadata, TracingSupport }
import com.github.levkhomich.akka.tracing.http.TracingHeaders

import scala.collection.JavaConversions._
import scala.collection.immutable.Set
import scala.concurrent.Await
import scala.util.Random

class PlayTracingSpec extends PlaySpecification with TracingTestCommons with MockCollector with Results with ResultMatchers {

  sequential

  val TestPath = "/request"
  val TestErrorPath = "/error"
  val npe = new NullPointerException

  implicit def trace: TracingExtensionImpl = TracingExtension(_root_.play.libs.Akka.system)

  val configuration = Map(
    TracingExtension.AkkaTracingPort -> collectorPort
  )
  private val routes: PartialFunction[RequestHeader, Action[AnyContent]] = {
    case play.api.test.Helpers.GET(p"/") =>
      Action {
        Ok(
          """
            |<html>
            |<body>
            |  <div id="title">Hello Guest</div>
            |  <a href="/login">click me</a>
            |</body>
            |</html>
          """.stripMargin) as "text/html"
      }
    case (GET, TestPath) =>
      Action {
        Ok("response") as "text/plain"
      }
    case (GET, TestErrorPath) =>
      Action {
        throw npe
        Ok("response") as "text/plain"
      }
  }

  val fakeApplication = GuiceApplicationBuilder()
    .configure(configuration)
    .router(Router.from(routes))
    .build()

  def overriddenApplication(overriddenServiceName: String = "test", queryParams: Set[String] = Set.empty, headerKeys: Set[String] = Set.empty) = {
    GuiceApplicationBuilder()
      .configure(configuration)
      .configure(Map("overriddenServiceName" -> overriddenServiceName,
        "queryParams" -> queryParams,
        "headerKeys" -> headerKeys))
      .router(Router.from(routes))
      .build()
  }

  "Play tracing" should {
    "sample requests" in new WithApplication(fakeApplication) {
      val result = route(app, FakeRequest(GET, TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      success
    }

    "use play application name as the default end point name" in new WithApplication(fakeApplication) {
      val result = route(app, FakeRequest("GET", TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      span.annotations.map(_.get_host().get_service_name()) must beEqualTo(_root_.play.libs.Akka.system.name)
    }

    "enable overriding the service name for the end point name" in new WithApplication(overriddenApplication(overriddenServiceName = "test service")) {
      val result = route(app, FakeRequest("GET", TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      span.annotations.map(_.get_host().get_service_name()) must beEqualTo("test service")
    }

    "not allow to use RequestHeaders as child of other request" in new WithApplication(fakeApplication) {
      val parent = new TracingSupport {}
      val request = FakeRequest("GET", TestPath)
      new PlayControllerTracing {
        request.asChildOf(parent)
      } must throwA[IllegalStateException]
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

    "exclude specific query values from annotations when configured" in new WithApplication(overriddenApplication(queryParams = Set("excludedParam"))) {
      val result = route(app, FakeRequest("GET", TestPath + "?key=value&excludedParam=value",
        FakeHeaders(Seq("Content-Type" -> "text/plain")), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkBinaryAnnotation(span, "request.query.key", "value")
      checkAbsentBinaryAnnotation(span, "request.query.excludedParam")
    }

    "exclude specific header fields from annotations when configured" in new WithApplication(overriddenApplication(headerKeys = Set("Excluded"))) {
      val result = route(app, FakeRequest("GET", TestPath,
        FakeHeaders(Seq(
          "Content-Type" -> "text/plain",
          "Excluded" -> "test",
          "Included" -> "value"
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkBinaryAnnotation(span, "request.headers.Included", "value")
      checkAbsentBinaryAnnotation(span, "request.headers.Excluded")
    }

    "propagate tracing headers" in new WithApplication(fakeApplication) {
      val spanId = Random.nextLong
      val parentId = Random.nextLong

      val result = route(app, FakeRequest("GET", TestPath + "?key=value",
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> SpanMetadata.idToString(spanId),
          TracingHeaders.ParentSpanId -> SpanMetadata.idToString(parentId)
        )), AnyContentAsEmpty)).map(Await.result(_, defaultAwaitTimeout.duration))

      val span = receiveSpan()
      checkBinaryAnnotation(span, "request.headers." + TracingHeaders.TraceId, SpanMetadata.idToString(spanId))
      checkBinaryAnnotation(span, "request.headers." + TracingHeaders.ParentSpanId, SpanMetadata.idToString(parentId))
    }

    "record server errors to traces" in new WithApplication(fakeApplication) {
      val result = route(app, FakeRequest("GET", TestErrorPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkAnnotation(span, TracingExtension.getStackTrace(npe))
    }

  }

  step {
    collector.stop()
  }

  /* // it seems that play-test doesn't call global.onRequestCompletion and global.onError
  override def call[T](action: EssentialAction, rh: RequestHeader, body: T)(implicit w: Writeable[T]): Future[Result] = {
    val rhWithCt = w.contentType.map(ct => rh.copy(
      headers = FakeHeaders((rh.headers.toMap + ("Content-Type" -> ct)).toSeq)
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
  }*/

}
