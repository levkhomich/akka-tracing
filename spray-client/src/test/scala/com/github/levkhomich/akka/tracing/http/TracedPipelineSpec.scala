package com.github.levkhomich.akka.tracing.http

import scala.concurrent.Future

import org.specs2.matcher.FutureMatchers
import org.specs2.mutable.Specification
import spray.client.pipelining._
import spray.http._

import com.github.levkhomich.akka.tracing._

class TracedPipelineSpec extends Specification with FutureMatchers with TracingTestCommons with TracingTestActorSystem with MockCollector { self =>

  sequential

  val mockedPipeline = new TracedSprayPipeline {
    val system = self.system
    override def sendAndReceive = {
      case x: HttpRequest =>
        Future.successful(HttpResponse(StatusCodes.OK, bodyEntity))
    }
  }

  val bodyEntity = spray.http.HttpEntity("test")

  "tracedPipeline directive" should {
    "generate a sampled span when pipeline is used" in {
      val parent = nextRandomMessage
      trace.sample(parent, "test trace", force = true)
      // TODO: avoid the need in such delays
      Thread.sleep(100)
      val parentSpanId = trace.getId(parent.tracingId).get.spanId

      mockedPipeline.tracedPipeline[String](parent)(Get("http://test.com"))
      trace.record(parent, TracingAnnotations.ServerSend)

      val spans = receiveSpans()
      spans.size mustEqual 2
      val parentSpan = spans.find(_.id == parentSpanId).get
      val childSpan = spans.filterNot(_ == parentSpan).head

      childSpan.trace_id mustEqual parentSpan.trace_id
      childSpan.parent_id mustEqual parentSpan.id
      childSpan.id mustNotEqual parentSpan.id
    }
  }

  step {
    shutdown()
  }
}

