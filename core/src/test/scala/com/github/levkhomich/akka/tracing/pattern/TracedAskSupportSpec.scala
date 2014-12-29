package com.github.levkhomich.akka.tracing.pattern

import scala.concurrent.duration.SECONDS

import akka.actor.Actor
import akka.testkit.TestActorRef
import akka.util.Timeout
import org.specs2.mutable.Specification

import com.github.levkhomich.akka.tracing._

class TracedAskSupportSpec extends Specification with TracingTestCommons with TracingTestActorSystem with MockCollector {

  sequential

  "TracedAskSupport" should {

    "instrument ask pattern (ActorRef)" in {
      val childActor = TestActorRef(new Actor {
        def receive = {
          case _: TracingSupport => sender ! "ok"
        }
      })

      val parentMessage = nextRandomMessage
      parentMessage.sample()
      val childMessage = nextRandomMessage.asChildOf(parentMessage)
      trace.sample(childMessage, "testService")

      implicit val timeout = Timeout(5, SECONDS)
      ask(childActor, childMessage)

      val span = receiveSpan()
      span.get_parent_id mustEqual parentMessage.$spanId
      checkAnnotation(span, "response: ok")
    }

    "instrument ask pattern (ActorSelection)" in {
      val childActor = {
        val ref = TestActorRef(new Actor {
          def receive = {
            case _: TracingSupport => sender ! "ok"
          }
        })
        system.actorSelection(ref.path)
      }

      val parentMessage = nextRandomMessage
      parentMessage.sample()
      val childMessage = nextRandomMessage.asChildOf(parentMessage)
      trace.sample(childMessage, "testService")

      implicit val timeout = Timeout(5, SECONDS)
      ask(childActor, childMessage)

      val span = receiveSpan()
      span.get_parent_id mustEqual parentMessage.$spanId
      checkAnnotation(span, "response: ok")
    }

  }

  step(shutdown())

}
