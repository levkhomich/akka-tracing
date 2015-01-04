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

import scala.util.Random

import org.specs2.mutable.Specification

class TracingSupportSpec extends Specification with TracingTestCommons {

  sequential

  "TracingSupport" should {

    val MessageCount = 1000000
    "provide unique (enough) tracing ids in worst case" in {
      val messages = (0 until MessageCount).map(_ => new TracingSupport {}).sortBy(_.tracingId).toList
      val collisionCount = messages.sliding(2).foldRight(0) {
        case (l :: r :: Nil, z) if l.tracingId != r.tracingId =>
          z
        case (l, z) =>
          z + 1
      }
      collisionCount.toDouble / MessageCount must be_<(0.0005)
    }

    "provide unique tracing ids in regular case" in {
      final case class Message(value: Int) extends TracingSupport
      val messages = (0 until MessageCount).map(_ => Message(Random.nextInt)).sortBy(_.tracingId).toList
      val collisionCount = messages.sliding(2).foldRight(0) {
        case (l :: r :: Nil, z) if l.tracingId != r.tracingId =>
          z
        case (l, z) =>
          z + 1
      }
      collisionCount mustEqual 0
    }

  }

}
