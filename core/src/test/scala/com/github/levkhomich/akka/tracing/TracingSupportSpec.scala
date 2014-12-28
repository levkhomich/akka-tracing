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

class TracingSupportSpec extends Specification with TracingTestCommons with TracingTestActorSystem with MockCollector {

  sequential

  "TracingSupport" should {

    "allow message sampling" in {
      val msg = nextRandomMessage
      msg.isSampled must beFalse

      msg.sample()
      msg.isSampled must beTrue

      msg.init(0L, 0L, Some(0L)) must throwAn[IllegalArgumentException]
      msg.asChildOf(nextRandomMessage) must throwAn[IllegalArgumentException]
    }

    "propagate context to child requests" in {
      val parent = nextRandomMessage
      parent.sample()

      val child = nextRandomMessage.asChildOf(parent)
      child.isSampled must beTrue

      child.$traceId mustEqual parent.$traceId
      child.$spanId mustNotEqual parent.$spanId
      child.$parentId mustEqual Some(parent.$spanId)
    }

    "instrument actor receive" in {
      val actor = TestActorRef(new Actor with ActorTracing {
        def receive = {
          case _ =>
        }
      })

      val message = nextRandomMessage

      trace.sample(message, "testService")
      actor ! message
      trace.finish(message)

      val span = receiveSpan()
      checkAnnotation(span, "request: " + message)
    }

    "support external contexts" in {
      val parent = nextRandomMessage
      parent.sample()
      val child = nextRandomMessage.asChildOf(parent)

      val childClone = nextRandomMessage
      childClone.init(child.$spanId, child.$traceId.get, child.$parentId)

      child.$spanId mustEqual childClone.$spanId
      child.$traceId mustEqual childClone.$traceId
      child.$parentId mustEqual childClone.$parentId
    }

  }

  step(shutdown())

}
