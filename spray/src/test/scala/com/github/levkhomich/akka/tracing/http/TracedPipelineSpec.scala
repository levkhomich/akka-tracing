package com.github.levkhomich.akka.tracing.http

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

import spray.http._
import spray.client.pipelining._
import scala.concurrent.Future
import com.github.levkhomich.akka.tracing._
import org.specs2.mutable.Specification
import org.specs2.matcher.FutureMatchers

class TracedPipelineSpec extends Specification with FutureMatchers with TracingTestCommons with TracingTestActorSystem with MockCollector { self =>

  sequential

  def mockedPipeline(mockResponse: HttpResponse) = new TracedSprayPipeline {
    val system = self.system
    override def sendAndReceive = {
      case x: HttpRequest =>
        Future.successful(mockResponse)
    }
  }

  val bodyEntity = spray.http.HttpEntity("test")

  "tracedPipeline" should {
    "Generate a sampled span when pipeline is used" in {
      val mockResponse = HttpResponse(StatusCodes.OK, bodyEntity)
      val pipeline = mockedPipeline(mockResponse)
      val mockTrigger = nextRandomMessage
      trace.forcedSample(mockTrigger, "test trace")
      pipeline.tracedPipeline[String](mockTrigger)(Get("http://test.com"))
      trace.finish(mockTrigger)
      expectSpans(2)
    }
  }

  step {
    shutdown()
  }
}

