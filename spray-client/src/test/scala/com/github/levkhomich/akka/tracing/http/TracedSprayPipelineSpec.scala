package com.github.levkhomich.akka.tracing.http

import scala.concurrent.{ Promise, Future }

import org.specs2.matcher.FutureMatchers
import org.specs2.mutable.Specification
import org.specs2.matcher.MatchResult
import spray.client.pipelining._
import spray.http._

import com.github.levkhomich.akka.tracing._

class TracedSprayPipelineSpec extends Specification with FutureMatchers with TracingTestCommons with TracingTestActorSystem with MockCollector { self =>

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

    "indicate to downstream service trace is not sampled if we are not sampling" in {
      val result = Promise[MatchResult[_]]()
      val parent = nextRandomMessage
      val testPipeline = new TracedSprayPipeline {
        val system = self.system
        override def sendAndReceive = {
          case req: HttpRequest =>
            result.trySuccess(
              (req.headers.find(_.name == TracingHeaders.Sampled).map(_.value) must beSome("false")) and
                (req.headers.find(_.name == TracingHeaders.TraceId).map(_.value) must not beNull)
            )
            Future.successful(HttpResponse(StatusCodes.OK, bodyEntity))
        }
      }
      testPipeline.tracedPipeline[String](parent)(Get("http://test.com"))
      result.future.await
    }

    "indicate to downstream service that we are sampled along with the trace id if we are sampling" in {
      val result = Promise[MatchResult[_]]()
      val parent = nextRandomMessage
      trace.sample(parent, "test trace", force = true)
      val testPipeline = new TracedSprayPipeline {
        val system = self.system
        override def sendAndReceive = {
          case req: HttpRequest =>
            result.trySuccess(
              (req.headers.find(_.name == TracingHeaders.Sampled).map(_.value) must beSome("true")) and
                (req.headers.find(_.name == TracingHeaders.TraceId).map(_.value) must not beNull)
            )
            Future.successful(HttpResponse(StatusCodes.OK, bodyEntity))
        }
      }
      testPipeline.tracedPipeline[String](parent)(Get("http://test.com"))
      result.future.await
    }
  }

  step {
    shutdown()
  }
}

