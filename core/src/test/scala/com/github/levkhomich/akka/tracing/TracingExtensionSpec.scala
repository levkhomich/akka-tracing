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

import scala.concurrent.duration.MILLISECONDS
import scala.util.Random

import akka.actor._
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

    "be disabled in case of wrong config" in {
      testActorSystem(maxSpansPerSecond = 0) should throwA[IllegalArgumentException]
      testActorSystem(maxSpansPerSecond = -1) should throwA[IllegalArgumentException]
      testActorSystem(maxSpansPerSecond = -100) should throwA[IllegalArgumentException]
      testActorSystem(maxSpansPerSecond = Int.MinValue) should throwA[IllegalArgumentException]
      testActorSystem(sampleRate = -1) should throwA[IllegalArgumentException]
      testActorSystem(sampleRate = -100) should throwA[IllegalArgumentException]
      success
    }

    "enable only if host specified" in {
      def expect(system: ActorSystem, enabled: Boolean): Unit = {
        system.extension(TracingExtension).isEnabled shouldEqual enabled
        system.shutdown()
      }
      expect(testActorSystem(tracingHost = None), enabled = false)
      expect(testActorSystem(tracingHost = Some(DefaultTracingHost)), enabled = true)
      success
    }

    "support `disabled` setting" in {
      def test(enabled: java.lang.Boolean): Unit = {
        val system = testActorSystem(settings = Map(TracingExtension.AkkaTracingEnabled -> enabled))
        system.extension(TracingExtension).isEnabled shouldEqual enabled
        system.shutdown()
      }
      test(true)
      test(false)
      success
    }

    "sample at specified rate" in {
      def generateTracesWithSampleRate(count: Int, sampleRate: Int): Unit = {
        val system = testActorSystem(sampleRate)
        generateTraces(count, TracingExtension(system))
        awaitSpans()
        terminateActorSystem(system)
      }

      generateTracesWithSampleRate(2, 1)
      generateTracesWithSampleRate(60, 2)
      generateTracesWithSampleRate(500, 5)

      expectSpans(132)
    }

    "allow forced sampling" in {
      val system = testActorSystem(sampleRate = Int.MaxValue)
      generateForcedTraces(100, TracingExtension(system))
      awaitSpans()
      terminateActorSystem(system)

      expectSpans(100)
    }

    def testTraceRecording(f: TracingSupport => Unit)(check: thrift.Span => MatchResult[_]): MatchResult[_] = {
      TestActorRef(new ActorTracing {
        override def receive: Receive = {
          case msg @ TestMessage(content) =>
            trace.sample(msg, "test")
            f(msg)
            trace.record(msg, TracingAnnotations.ServerSend)
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

      val childMsg = nextRandomMessage
      trace.createChild(childMsg, parentMsg)
      testActor ! childMsg

      trace.record(parentMsg, TracingAnnotations.ServerSend)
      val parentSpan = receiveSpans().head

      trace.record(childMsg, TracingAnnotations.ServerSend)
      val childSpan = receiveSpans().head

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

    "send traces after calling flush" in {
      val testActor = TestActorRef(new ActorTracing {
        override def receive: Receive = {
          case msg @ TestMessage(content) =>
            trace.sample(msg, "test")
        }
      })
      def getDelay: Long = {
        val start = System.currentTimeMillis
        val msg = nextRandomMessage
        testActor ! msg
        results.size shouldEqual 0
        trace.flush(msg)
        expectSpans(1)
        System.currentTimeMillis - start
      }
      val messageCount = 100
      val avgDelay = (0 until messageCount).map(_ => getDelay).sum / messageCount
      avgDelay should be lessThan 5000
    }

    "support raw spans" in {
      val span = new thrift.Span(Random.nextLong, Random.nextString(10), Random.nextLong, null, null)
      trace.submitSpans(Seq(span))
      val received = receiveSpan()
      received shouldEqual span
    }

    "handle collector connectivity problems" in {
      collector.stop()
      generateTraces(100, trace)
      // wait until spans are sent while collector is down
      awaitSpans()

      collector = startCollector()
      expectSpans(100)
    }

    "flush traces before stop" in {
      generateTraces(10, trace)
      Thread.sleep(500)
      terminateActorSystem(system)
      expectSpans(10)
    }
  }

  step(collector.stop())

}
