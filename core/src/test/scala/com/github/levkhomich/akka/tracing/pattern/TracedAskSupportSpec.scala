package com.github.levkhomich.akka.tracing.pattern

import scala.concurrent.duration.SECONDS

import akka.actor.Actor
import akka.testkit.TestActorRef
import akka.util.Timeout
import org.specs2.mutable.Specification
import scala.concurrent._
import ExecutionContext.Implicits.global

import com.github.levkhomich.akka.tracing._

class TracedAskSupportSpec extends Specification with TracingTestCommons with TracingTestActorSystem with MockCollector {

  sequential

  "TracedAskSupport" should {

    "instrument ask pattern (ActorRef)" in {
      val childActor = TestActorRef(new Actor {
        def receive: Receive = {
          case _: TracingSupport => sender ! "ok"
        }
      })

      val parentMessage = nextRandomMessage
      trace.sample(parentMessage, "testService")

      Thread.sleep(100)

      val childMessage = nextRandomMessage
      trace.createChild(childMessage, parentMessage)
      trace.sample(childMessage, "testService")

      trace.record(parentMessage, TracingAnnotations.ServerSend)
      val parentSpan = receiveSpan()

      implicit val timeout = Timeout(5, SECONDS)
      ask(childActor, childMessage)

      val span = receiveSpan()
      span.get_parent_id mustEqual parentSpan.get_id
      checkAnnotation(span, "response: ok")
    }

    "instrument ask pattern (ActorSelection)" in {
      val childActor = {
        val ref = TestActorRef(new Actor {
          def receive: Receive = {
            case _: TracingSupport => sender ! "ok"
          }
        })
        system.actorSelection(ref.path)
      }

      val parentMessage = nextRandomMessage
      trace.sample(parentMessage, "testService")

      Thread.sleep(100)

      val childMessage = nextRandomMessage
      trace.createChild(childMessage, parentMessage)
      trace.sample(childMessage, "testService")

      trace.record(parentMessage, TracingAnnotations.ServerSend)
      val parentSpan = receiveSpan()

      implicit val timeout = Timeout(5, SECONDS)
      ask(childActor, childMessage)

      val span = receiveSpan()
      span.get_parent_id mustEqual parentSpan.get_id
      checkAnnotation(span, "response: ok")
    }

  }

  step(shutdown())

}
