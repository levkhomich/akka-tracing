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

import java.util.UUID

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification

abstract class AkkaTracingSpecification extends Specification {

  def testActorSystem(sampleRate: Int = 1): ActorSystem =
    ActorSystem("AkkaTracingTestSystem" + sampleRate,
      ConfigFactory.parseMap(scala.collection.JavaConversions.mapAsJavaMap(
        Map(TracingExtension.AkkaTracingSampleRate -> sampleRate)
      ))
    )

  def generateTraces(count: Int, trace: TracingExtensionImpl): Unit = {
    println(s"sending $count messages")
    for (_ <- 1 to count) {
      val msg = StringMessage(UUID.randomUUID().toString)
      trace.sample(msg, "test")
      trace.finish(msg)
    }
  }

}
