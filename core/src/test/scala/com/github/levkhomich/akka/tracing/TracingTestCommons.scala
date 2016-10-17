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

import scala.concurrent.{ Await, TimeoutException }
import scala.concurrent.duration._
import scala.util.Random

import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

final case class TestMessage(value: String) extends TracingSupport

trait TracingTestCommons { this: Specification =>

  val SystemName = "AkkaTracingTestSystem"
  val DefaultTracingHost = "localhost"
  val SomeValue = Random.nextLong().toString

  val ciEnvironment = "true".equals(System.getenv("CI"))

  def nextRandomMessage: TestMessage =
    TestMessage(SomeValue)

  def testConfig(sampleRate: Int = 1, settings: Map[String, AnyRef] = Map.empty, maxSpansPerSecond: Long = 10000L,
                 tracingHost: Option[String] = Some(DefaultTracingHost)): Config =
    ConfigFactory.parseMap(scala.collection.JavaConversions.mapAsJavaMap(
      Map(
        TracingExtension.AkkaTracingSampleRate -> sampleRate,
        TracingExtension.AkkaTracingMaxSpansPerSecond -> maxSpansPerSecond,
        TracingExtension.AkkaTracingPort -> (this match {
          case mc: MockCollector =>
            mc.collectorPort
          case _ =>
            9410
        }),
        "akka.test.default-timeout" -> "2000 ms",
        "akka.test.timefactor" -> (if (ciEnvironment) "4" else "1")
      ) ++ settings ++ tracingHost.map(TracingExtension.AkkaTracingHost -> _).toMap
    ))

  def testActorSystem(sampleRate: Int = 1, settings: Map[String, AnyRef] = Map.empty, maxSpansPerSecond: Long = 10000L,
                      tracingHost: Option[String] = Some(DefaultTracingHost)): ActorSystem = {
    val system = ActorSystem(SystemName, testConfig(sampleRate, settings, maxSpansPerSecond, tracingHost))
    // wait for system to boot
    Thread.sleep(50)
    system
  }

  def terminateActorSystem(system: ActorSystem): MatchResult[_] = {
    Await.result(system.terminate(), 5.seconds) must not(throwA[TimeoutException])
  }

  def generateTraces(count: Int, trace: TracingExtensionImpl): Unit = {
    println(s"generating $count trace${if (count > 1) "s" else ""}")
    for (_ <- 0 until count) {
      val msg = nextRandomMessage
      trace.sample(msg, "test")
      trace.record(msg, TracingAnnotations.ServerSend)
    }
  }

  def generateForcedTraces(count: Int, trace: TracingExtensionImpl): Unit = {
    println(s"generating $count forced trace${if (count > 1) "s" else ""}")
    for (_ <- 0 until count) {
      val msg = nextRandomMessage
      trace.sample(msg, "test", force = true)
      trace.record(msg, TracingAnnotations.ServerSend)
    }
  }

}

trait TracingTestActorSystem { this: TracingTestCommons with Specification =>

  val sampleRate = 1

  implicit lazy val system = testActorSystem(sampleRate)
  implicit lazy val trace = TracingExtension(system)

  def shutdown(): Unit = {
    this match {
      case mc: MockCollector =>
        mc.collector.stop()
      case _ =>
    }
    terminateActorSystem(system)
  }
}