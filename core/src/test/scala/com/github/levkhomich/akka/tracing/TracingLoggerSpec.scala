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

import akka.testkit.TestActorRef
import org.specs2.mutable.Specification

class TracingLoggerSpec extends Specification with TracingTestCommons with TracingTestActorSystem with MockCollector {

  sequential

  "TracingLogger" should {

    "pipe logs to traces" in {
      val testActor = TestActorRef(new ActorTracing with TracingActorLogging {
        override def receive: Receive = {
          case msg @ TestMessage(content) =>
            trace.sample(msg, "test")
            log.info("received message " + msg)
            // otherwise span can be closed before log record processed
            Thread.sleep(500)
            trace.record(msg, TracingAnnotations.ServerSend)
        }
      })

      for (_ <- 1 to 3) {
        testActor ! nextRandomMessage
      }

      awaitSpans()

      val spans = receiveSpans()

      spans.size must beEqualTo(3)
      spans.forall { span =>
        span.annotations.size == 3 && span.annotations.get(1).value.startsWith("Info")
      } must beTrue
    }

    "work as a normal logger outside of traced requests" in {
      val testActor = TestActorRef(new ActorTracing with TracingActorLogging {
        override def receive: Receive = {
          case msg: String =>
            log.info("received message " + msg)
        }
      })
      testActor ! ""
      Thread.sleep(100)
      testActor.underlying.isTerminated mustEqual false
    }
  }

  step(shutdown())

}
