package com.github.levkhomich.akka.tracing.http

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.StatusCodes
import com.typesafe.config.Config
import org.specs2.mutable.Specification

import com.github.levkhomich.akka.tracing._

class ForcedSamplingSpec extends Specification with TracingTestCommons
    with BaseTracingDirectives with MockCollector with Specs2FrameworkInterface {

  import ForcedSamplingSpec._

  sequential

  override def testConfig: Config = testConfig(sampleRate = SpanCount)

  "Akka HTTP tracedHandleWith directive" should {

    "force sampling of requests with X-B3-Sampled: true header" in {
      for (_ <- 0 until SpanCount) {
        Get(testPath).withHeaders(
          RawHeader(TracingHeaders.Sampled, true.toString)
        ) ~> tracedHandleWithRoute ~> check {
            response.status mustEqual StatusCodes.OK
          }
      }
      expectSpans(SpanCount)
    }

    "avoid sampling of requests with X-B3-Sampled: false header" in {
      for (_ <- 0 until SpanCount) {
        Get(testPath).withHeaders(
          RawHeader(TracingHeaders.Sampled, false.toString)
        ) ~> tracedHandleWithRoute ~> check {
            response.status mustEqual StatusCodes.OK
          }
      }
      expectSpans(0)
    }

    "force sampling of requests with X-B3-Flags containing Debug flag" in {
      for (i <- 0 until SpanCount) {
        Get(testPath).withHeaders(
          RawHeader(TracingHeaders.Flags, (i | TracingHeaders.DebugFlag).toString)
        ) ~> tracedHandleWithRoute ~> check {
            response.status mustEqual StatusCodes.OK
          }
      }
      expectSpans(SpanCount)
    }

    "sample requests with X-B3-Flags not containing Debug flag as regular" in {
      for (i <- 0 until SpanCount) {
        Get(testPath).withHeaders(
          RawHeader(TracingHeaders.Flags, (i & ~TracingHeaders.DebugFlag).toString)
        ) ~> tracedHandleWithRoute ~> check {
            response.status mustEqual StatusCodes.OK
          }
      }
      expectSpans(1)
    }

  }

  step {
    collector.stop()
    terminateActorSystem(system)
  }

}

object ForcedSamplingSpec {
  val SpanCount = 100
}