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

import java.util.concurrent.TimeoutException
import scala.collection.JavaConversions._
import scala.concurrent.duration.{ FiniteDuration, SECONDS }
import scala.util.Random

import akka.testkit.TestActorRef
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

class TracingExtensionSpec extends Specification with TracingTestCommons with TracingTestActorSystem with MockCollector {

  sequential

  "TracingExtension" should {

    "sample at specified rate" in {
      def generateTracesWithSampleRate(count: Int, sampleRate: Int): Unit = {
        val system = testActorSystem(sampleRate)
        generateTraces(count, TracingExtension(system))
        Thread.sleep(3000)
        system.shutdown()
        system.awaitTermination(FiniteDuration(5, SECONDS)) must not(throwA[TimeoutException])
      }

      generateTracesWithSampleRate(2, 1)
      generateTracesWithSampleRate(60, 2)
      generateTracesWithSampleRate(500, 5)

      results.size() must beEqualTo(132)
    }

    def testBinaryAnnotation(f: TracingSupport => Unit)(check: thrift.Span => MatchResult[_]): MatchResult[_] = {
      results.clear()
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

    "support binary annotations (String)" in {
      val value = Random.nextLong.toString
      testBinaryAnnotation(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "support binary annotations (Long)" in {
      val value = Random.nextLong
      testBinaryAnnotation(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "support binary annotations (Int)" in {
      val value = Random.nextInt
      testBinaryAnnotation(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "support binary annotations (Short)" in {
      val value = Random.nextInt.toShort
      testBinaryAnnotation(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "support binary annotations (Boolean)" in {
      val value = Random.nextBoolean
      testBinaryAnnotation(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "support binary annotations (Array[Byte])" in {
      val value = Random.nextLong.toString.getBytes
      testBinaryAnnotation(trace.recordKeyValue(_, key, value))(checkBinaryAnnotation(_, key, value))
    }

    "trace nested calls" in {
      results.clear()

      val testActor = TestActorRef(new ActorTracing {
        override def receive: Receive = {
          case msg @ TestMessage(content) =>
            trace.sample(msg, "test")
            trace.recordKeyValue(msg, "content", content)
            trace.finish(msg)
        }
      })

      val parentMsg = nextRandomMessage
      testActor ! parentMsg

      // wait until parent msg span will be sent
      Thread.sleep(500)

      val childMsg = nextRandomMessage.asChildOf(parentMsg)
      testActor ! childMsg

      Thread.sleep(5000)

      results.size() must beEqualTo(2)

      val spans = results.map(e => decodeSpan(e.message))
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

      parentSpan.id must beEqualTo(parentMsg.$spanId)
      parentSpan.is_set_parent_id must beFalse
      parentSpan.trace_id must beEqualTo(parentMsg.$traceId.get)

      childSpan.id must beEqualTo(childMsg.$spanId)
      childSpan.parent_id must beEqualTo(parentMsg.$spanId)
      childSpan.trace_id must beEqualTo(parentMsg.$traceId.get)

      parentMsg.$traceId must beEqualTo(childMsg.$traceId)
    }

    "handle collector connectivity problems" in {
      // collector won't stop until some message's arrival
      generateTraces(1, trace)
      collector.stop()

      Thread.sleep(3000)
      results.clear()

      generateTraces(100, trace)

      // wait for submission while collector is down
      Thread.sleep(3000)

      collector = startCollector()
      Thread.sleep(3000)

      // extension should wait for some time before retrying
      results.size() must beEqualTo(0)

      Thread.sleep(7000)

      results.size() must beEqualTo(100)
    }
  }

  step(shutdown())

}
