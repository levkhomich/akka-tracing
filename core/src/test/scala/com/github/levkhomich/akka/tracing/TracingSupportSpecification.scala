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
import scala.concurrent.duration.{ FiniteDuration, SECONDS }
import scala.util.Random

import akka.actor.Actor
import akka.testkit.TestActorRef
import akka.util.Timeout
import org.specs2.mutable.Specification

class TracingSupportSpecification extends Specification with AkkaTracingSpecification with MockCollector {

  implicit val system = testActorSystem()
  implicit val trace = TracingExtension(system)

  sequential

  final case class TestMessage(value: String) extends TracingSupport

  def newMessage: TracingSupport =
    TestMessage(Random.nextLong.toString)

  "TracingSupport" should {

    "allow message sampling" in {
      val msg = newMessage
      msg.isSampled must beFalse

      msg.sample()
      msg.isSampled must beTrue

      msg.init(0L, 0L, Some(0L)) must throwAn[IllegalArgumentException]
      msg.asChildOf(newMessage) must throwAn[IllegalArgumentException]
    }

    "propagate context for child requests" in {
      val parent = newMessage
      parent.sample()

      val child = newMessage.asChildOf(parent)
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

      val message = newMessage

      trace.sample(message, "testService")
      actor ! message
      trace.finish(message)

      val span = receiveSpan()
      checkAnnotation(span, "request: " + message)
    }

    "instrument ask pattern (ActorRef)" in {
      import pattern.ask
      val childActor = TestActorRef(new Actor {
        def receive = {
          case _: TracingSupport => sender ! "ok"
        }
      })

      val parentMessage = newMessage
      parentMessage.sample()
      val childMessage = newMessage.asChildOf(parentMessage)
      trace.sample(childMessage, "testService")

      implicit val timeout = Timeout(5, SECONDS)
      childActor ? childMessage

      val span = receiveSpan()
      span.get_parent_id mustEqual parentMessage.$spanId
      checkAnnotation(span, "response: ok")
    }

    "instrument ask pattern (ActorSelection)" in {
      import pattern.ask
      val childActor = {
        val ref = TestActorRef(new Actor {
          def receive = {
            case _: TracingSupport => sender ! "ok"
          }
        })
        system.actorSelection(ref.path)
      }

      val parentMessage = newMessage
      parentMessage.sample()
      val childMessage = newMessage.asChildOf(parentMessage)
      trace.sample(childMessage, "testService")

      implicit val timeout = Timeout(5, SECONDS)
      childActor ? childMessage

      val span = receiveSpan()
      span.get_parent_id mustEqual parentMessage.$spanId
      checkAnnotation(span, "response: ok")
    }

    "support external contexts" in {
      val parent = newMessage
      parent.sample()
      val child = newMessage.asChildOf(parent)

      val childClone = newMessage
      childClone.init(child.$spanId, child.$traceId.get, child.$parentId)

      child.$spanId mustEqual childClone.$spanId
      child.$traceId mustEqual childClone.$traceId
      child.$parentId mustEqual childClone.$parentId
    }

  }

  "shutdown correctly" in {
    system.shutdown()
    system.awaitTermination(FiniteDuration(5, SECONDS)) must not(throwA[TimeoutException])
  }

}
