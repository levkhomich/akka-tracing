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

import akka.actor.Actor
import akka.testkit.TestActorRef
import org.specs2.mutable.Specification

class ActorTracingSpec extends Specification with TracingTestCommons with TracingTestActorSystem with MockCollector {

  sequential

  "ActorTracing" should {

    "instrument actor receive" in {
      val actor = TestActorRef(new Actor with ActorTracing {
        def receive: Receive = {
          case _ =>
        }
      })

      val message = nextRandomMessage

      trace.sample(message, "testService")
      actor ! message
      trace.finish(message)

      val span = receiveSpan()
      span.get_annotations.size mustEqual 2
      span.get_binary_annotations mustEqual null
    }

  }

  step(shutdown())

}
