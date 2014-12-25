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

class TracingSupportSpecification extends AkkaTracingSpecification {

  val system = testActorSystem()
  implicit val trace = TracingExtension(system)

  sequential

  def newMessage: TracingSupport =
    new TracingSupport {}

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
