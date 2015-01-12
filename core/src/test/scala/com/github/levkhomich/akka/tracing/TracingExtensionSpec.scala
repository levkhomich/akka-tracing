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

package com.github.levkhomich.akka.tracing

import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import scala.collection.JavaConversions._
import scala.concurrent.duration.{ FiniteDuration, MILLISECONDS, SECONDS }
import scala.util.Random

import akka.testkit.TestActorRef
import akka.util.Timeout
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

class TracingExtensionSpec extends Specification with TracingTestCommons with TracingTestActorSystem with MockCollector {

  sequential

  "TracingExtension" should {

    "be obtainable using Akka Plugin API" in {
      TracingExtension(system) mustEqual TracingExtension.get(system)
    }

    "sample at specified rate" in {
      def generateTracesWithSampleRate(count: Int, sampleRate: Int): Unit = {
        val system = testActorSystem(sampleRate)
        generateTraces(count, TracingExtension(system))
        awaitSpanSubmission()
        system.shutdown()
        system.awaitTermination(FiniteDuration(5, SECONDS)) must not(throwA[TimeoutException])
      }

      generateTracesWithSampleRate(2, 1)
      generateTracesWithSampleRate(60, 2)
      generateTracesWithSampleRate(500, 5)

      expectSpans(132)
    }

    "allow forced sampling" in {
      val system = testActorSystem(sampleRate = Int.MaxValue)
      generateForcedTraces(100, TracingExtension(system))
      awaitSpanSubmission()
      system.shutdown()
      system.awaitTermination(FiniteDuration(5, SECONDS)) must not(throwA[TimeoutException])

      expectSpans(100)
    }

    def testTraceRecording(f: TracingSupport => Unit)(check: thrift.Span => MatchResult[_]): MatchResult[_] = {
      TestActorRef(new ActorTracing {
        override def receive: Receive = {
          case msg @ TestMessage(content) =>
            trace.sample(msg, "test")
            f(msg)
            trace.finish(msg)
        }
      }) ! TestMessage("")
      check(receiveSpan())
    }
    val key = "key"

    "support annotations (Throwable)" in {
      val value = new NumberFormatException()
      testTraceRecording(trace.record(_, value))(checkAnnotation(_, TracingExtension.getStackTrace(value)))
    }

    "support binary annotations (String)" in {
      val value = Random.nextLong.toString
      testTraceRecording(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "support binary annotations (Double)" in {
      val value = Random.nextDouble
      testTraceRecording(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "support binary annotations (Long)" in {
      val value = Random.nextLong
      testTraceRecording(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "support binary annotations (Int)" in {
      val value = Random.nextInt
      testTraceRecording(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "support binary annotations (Short)" in {
      val value = Random.nextInt.toShort
      testTraceRecording(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "support binary annotations (Boolean)" in {
      val value = Random.nextBoolean
      testTraceRecording(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "support binary annotations (Array[Byte])" in {
      val value = Random.nextLong.toString.getBytes
      testTraceRecording(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "support binary annotations (ByteBuffer)" in {
      val value = ByteBuffer.wrap(Random.nextLong.toString.getBytes)
      testTraceRecording(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "trace nested calls" in {
      val testActor = TestActorRef(new ActorTracing {
        override def receive: Receive = {
          case msg @ TestMessage(content) =>
            trace.sample(msg, "test")
            trace.recordKeyValue(msg, "content", content)
        }
      })

      val parentMsg = nextRandomMessage
      testActor ! parentMsg

      // wait until parent msg span will be sent
      Thread.sleep(500)

      val childMsg = nextRandomMessage.asChildOf(parentMsg)
      testActor ! childMsg

      trace.finish(parentMsg)
      trace.finish(childMsg)

      val spans = receiveSpans()
      spans.size must beEqualTo(2)

      val parentSpan = spans.find { s =>
        s.binary_annotations != null && {
          val content = s.binary_annotations.find(_.key == "content").get.value
          new String(content.array()) == parentMsg.value
        }
      }.get
      val childSpan = spans.find { s =>
        s.binary_annotations != null && {
          val content = s.binary_annotations.find(_.key == "content").get.value
          new String(content.array()) == childMsg.value
        }
      }.get

      parentSpan.is_set_parent_id must beFalse

      childSpan.parent_id must beEqualTo(parentSpan.get_id)
      childSpan.trace_id must beEqualTo(parentSpan.get_trace_id)
    }

    "finish corresponding traces after calling asResponseTo" in {
      import akka.pattern.ask
      val testActor = TestActorRef(new ActorTracing {
        override def receive: Receive = {
          case msg @ TestMessage(content) =>
            trace.sample(msg, "test")
            sender ! None.asResponseTo(msg)
        }
      })
      val messageCount = 100
      implicit val timeout = Timeout(100, MILLISECONDS)
      for (_ <- 0 until messageCount) {
        testActor ? nextRandomMessage
      }
      expectSpans(messageCount)
    }

    "handle collector connectivity problems" in {
      // collector won't stop until some message's arrival
      generateTraces(1, trace)
      collector.stop()

      awaitSpanSubmission()
      results.clear()

      generateTraces(100, trace)

      // wait for submission while collector is down
      awaitSpanSubmission()

      collector = startCollector()

      // extension should wait for some time before retrying
      expectSpans(0)
      expectSpans(100)
    }

    "limit size of span submission buffer" in {
      // collector won't stop until some message's arrival
      generateTraces(1, trace)
      collector.stop()

      awaitSpanSubmission()
      results.clear()

      generateTraces(5000, trace)

      // wait for submission while collector is down (it can be 2 batches)
      expectSpans(0)

      collector = startCollector()

      expectSpans(1000, 2000)
    }

    "flush traces before stop" in {
      generateTraces(10, trace)
      Thread.sleep(100)
      system.shutdown()
      system.awaitTermination(FiniteDuration(500, MILLISECONDS)) must not(throwA[TimeoutException])
      expectSpans(10)
    }
  }

  step(collector.stop())

}
