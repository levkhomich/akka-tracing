package com.github.levkhomich.akka.tracing.http

import scala.concurrent.Future

import org.specs2.matcher.FutureMatchers
import org.specs2.mutable.Specification
import spray.client.pipelining._
import spray.http._

import com.github.levkhomich.akka.tracing._

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
      Thread.sleep(100)
      pipeline.tracedPipeline[String](mockTrigger)(Get("http://test.com"))
      trace.finish(mockTrigger)
      expectSpans(2)
    }
  }

  step {
    shutdown()
  }
}

