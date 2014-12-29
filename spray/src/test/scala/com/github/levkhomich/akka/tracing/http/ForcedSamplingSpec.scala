package com.github.levkhomich.akka.tracing.http

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

import com.github.levkhomich.akka.tracing._
import org.specs2.mutable.Specification
import spray.http._
import spray.httpx.unmarshalling.{ Deserialized, FromRequestUnmarshaller }
import spray.routing.HttpService
import spray.testkit.Specs2RouteTest

class ForcedSamplingSpec extends Specification with TracingTestCommons
    with BaseTracingDirectives with MockCollector with Specs2RouteTest with HttpService {

  sequential

  override implicit val system = testActorSystem(sampleRate = Int.MaxValue)
  override val actorRefFactory = system

  override protected def trace: TracingExtensionImpl =
    TracingExtension(system)

  val tracedHandleWithRoute =
    get {
      tracedHandleWith("testService") { r: TestMessage =>
        HttpResponse(StatusCodes.OK)
      }
    }

  val tracedCompleteRoute =
    get {
      tracedComplete("testService", "testRpc")(HttpResponse(StatusCodes.OK))
    }

  "tracedHandleWith directive" should {
    val SpanCount = 100

    "force sampling of requests with X-B3-Sampled = true" in {
      results.clear()
      for (_ <- 0 until SpanCount) {
        Get().withHeaders(
          HttpHeaders.RawHeader(TracingHeaders.Sampled, true.toString)
        ) ~> tracedHandleWithRoute ~> check {
            response.status mustEqual StatusCodes.OK
          }
      }
      Thread.sleep(3000)
      results.size mustEqual SpanCount
    }

    "force sampling of requests with X-B3-Flags containing Debug flag" in {
      results.clear()
      for (i <- 0 until SpanCount) {
        Get().withHeaders(
          HttpHeaders.RawHeader(TracingHeaders.Flags, (i | TracingHeaders.DebugFlag).toString)
        ) ~> tracedHandleWithRoute ~> check {
            response.status mustEqual StatusCodes.OK
          }
      }
      Thread.sleep(3000)
      results.size mustEqual SpanCount
    }

  }

  "tracedComplete directive" should {
    val SpanCount = 100

    "force sampling of requests with X-B3-Sampled = true" in {
      results.clear()
      for (_ <- 0 until SpanCount) {
        Get().withHeaders(
          HttpHeaders.RawHeader(TracingHeaders.Sampled, true.toString)
        ) ~> tracedCompleteRoute ~> check {
            response.status mustEqual StatusCodes.OK
          }
      }
      Thread.sleep(3000)
      results.size mustEqual SpanCount
    }

    "force sampling of requests with X-B3-Flags containing Debug flag" in {
      results.clear()
      for (i <- 0 until SpanCount) {
        Get().withHeaders(
          HttpHeaders.RawHeader(TracingHeaders.Flags, (i | TracingHeaders.DebugFlag).toString)
        ) ~> tracedCompleteRoute ~> check {
            response.status mustEqual StatusCodes.OK
          }
      }
      Thread.sleep(3000)
      results.size mustEqual SpanCount
    }

  }

  step {
    system.shutdown()
    collector.stop()
    system.awaitTermination(FiniteDuration(5, SECONDS)) must not(throwA[TimeoutException])
  }

  implicit def um: FromRequestUnmarshaller[TestMessage] =
    new FromRequestUnmarshaller[TestMessage] {
      override def apply(request: HttpRequest): Deserialized[TestMessage] =
        Right(TestMessage(request.entity.asString))
    }

}
