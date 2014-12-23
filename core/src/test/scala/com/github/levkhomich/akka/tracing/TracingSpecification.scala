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
import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration

import akka.actor.{ActorRef, Props, ActorSystem}

case class StringMessage(content: String) extends TracingSupport {
  override def spanName: String =
    "message-" + Math.abs(content.hashCode) % 50
}

class TestActor extends TracingActorLogging with ActorTracing {
  override def receive: Receive = {
    case msg @ StringMessage(content) =>
      trace.sample(msg, "test")
      trace.recordKeyValue(msg, "content", content)
      log.info("received message " + msg)
      Thread.sleep(100)
      trace.finish(msg)
  }
}

class TracingSpecification extends AkkaTracingSpecification with MockCollector {

  val system: ActorSystem = testActorSystem()
  implicit val trace = TracingExtension(system)

  sequential

  "TracingExtension" should {

    "sample at specified rate" in {
      def generateTracesWithSampleRate(count: Int, sampleRate: Int): Unit = {
        val system = testActorSystem(sampleRate)
        generateTraces(count, TracingExtension(system))
        Thread.sleep(3000)
        system.shutdown()
        system.awaitTermination(FiniteDuration(5, duration.SECONDS)) must not(throwA[TimeoutException])
      }

      generateTracesWithSampleRate(2, 1)
      generateTracesWithSampleRate(60, 2)
      generateTracesWithSampleRate(500, 5)

      results.size() must beEqualTo(132)
    }

    "pipe logs to traces" in {
      results.clear()

      val testActor = system.actorOf(Props[TestActor])

      testActor ! StringMessage("1")
      testActor ! StringMessage("2")
      testActor ! StringMessage("3")

      Thread.sleep(5000)

      results.size() must beEqualTo(3)
      results.forall { e =>
        val span = decodeSpan(e.message)
        span.annotations.size == 3 && span.annotations.get(1).value.startsWith("Info")
      } must beTrue
    }

    "track call hierarchy" in {
      results.clear()

      val testActor: ActorRef = system.actorOf(Props[TestActor])

      val parentMsg = StringMessage("parent")
      testActor ! parentMsg

      // wait until parent msg span will be sent
      Thread.sleep(500)

      val childMsg = StringMessage("child").asChildOf(parentMsg)
      testActor ! childMsg

      Thread.sleep(5000)

      results.size() must beEqualTo(2)

      val spans = results.map(e => decodeSpan(e.message))
      val parentSpan = spans.find { s =>
        s.binary_annotations != null && {
          val content = s.binary_annotations.find(_.key == "content").get.value
          new String(content.array()) == "parent"
        }
      }.get
      val childSpan = spans.find { s =>
        s.binary_annotations != null && {
          val content = s.binary_annotations.find(_.key == "content").get.value
          new String(content.array()) == "child"
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

  "shutdown correctly" in {
    system.shutdown()
    collector.stop()
    system.awaitTermination(FiniteDuration(5, duration.SECONDS)) must not(throwA[TimeoutException])
  }

}
