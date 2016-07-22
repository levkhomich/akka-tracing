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

import scala.concurrent._
import scala.concurrent.duration._

import akka.actor.{ Props, Actor }
import akka.testkit.TestActorRef
import akka.util.Timeout
import org.specs2.mutable.Specification

class TracingSupportSerializerSpec extends Specification with TracingTestCommons with MockCollector {

  sequential
  "TracingSupportSerializer" should {
    val baseConfig = Map(
      "akka.actor.provider" -> "akka.remote.RemoteActorRefProvider",
      "akka.remote.enabled-transports" -> scala.collection.JavaConversions.seqAsJavaList(Seq(
        "akka.remote.netty.tcp"
      )),
      "akka.remote.netty.tcp.hostname" -> "localhost"
    )

    lazy val system1 = testActorSystem(1, baseConfig +
      ("akka.remote.netty.tcp.port" -> (2552: java.lang.Integer)))
    lazy val trace1 = TracingExtension(system1)

    lazy val system2 = testActorSystem(1, baseConfig +
      ("akka.remote.netty.tcp.port" -> (2553: java.lang.Integer)))
    lazy val trace2 = TracingExtension(system2)

    "instrument actor receive" in {
      val actor1 = TestActorRef.create(system1, Props[Actor1], "actor1")
      val actor2 = TestActorRef.create(system2, Props[Actor2], "actor2")

      expectSpans(0)

      val message = TestMessage("parent")

      trace2.sample(message, "testService")
      actor2 ! message
      trace2.record(message, TracingAnnotations.ServerSend)

      expectSpans(2)
    }

    step {
      collector.stop()
      terminateActorSystem(system1)
      terminateActorSystem(system2)
    }
  }

}

class Actor1 extends Actor with ActorTracing {
  def receive: Receive = {
    case r: TracingSupport =>
      trace.record(r, "child annotation")
      sender() ! TestMessage("parent").asResponseTo(r)
  }
}

class Actor2 extends Actor with ActorTracing {
  def receive: Receive = {
    case r: TracingSupport =>
      import akka.pattern.ask
      import context.dispatcher
      implicit val timeout = Timeout(5, SECONDS)
      val selection =
        context.actorSelection(s"akka.tcp://AkkaTracingTestSystem@localhost:2552/user/actor1")
      trace.record(r, "parent annotation")
      val childMessage = TestMessage("child")
      trace.createChild(childMessage, r)
      selection ? childMessage
  }
}