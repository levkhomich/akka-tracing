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

import org.specs2.mutable.Specification
import scala.util.Random

class SpanIdSpecification extends Specification {

  sequential

  val IterationsCount = 100000L

  "SpanId" should {
    "provide serialization conforming to Finagle's implementation" in {
      def checkValue(x: Long): Unit =
        if (Span.asString(x) != new com.twitter.finagle.tracing.SpanId(x).toString())
          failure("SpanId serialization failed for value " + x)

      for (_ <- 1L to IterationsCount)
        checkValue(Random.nextLong())

      checkValue(Long.MaxValue)
      checkValue(Long.MinValue)
      checkValue(0)
      checkValue(10)
      checkValue(100000)
      checkValue(-10)
      checkValue(-100000)

      success
    }
    "serialization faster than naive implementation" in {
      def naiveLongToString(x: Long): String = {
        val s = java.lang.Long.toHexString(x)
        "0" * (16 - s.length) + s
      }

      def benchmark(f: Long => String): Long = {
        val nanos = System.nanoTime
        for (i <- 1L to IterationsCount) {
          val _ = f(i)
        }
        IterationsCount * 100000000 / (System.nanoTime - nanos)
      }

      val originalCPS = benchmark(Span.asString)
      val naiveCPS = benchmark(naiveLongToString)
      originalCPS must beGreaterThan(naiveCPS)
    }
    "provide correct deserialization" in {
      def checkValue(x: Long): Unit =
        if (Span.fromString(Span.asString(x)) != x)
          failure("SpanId deserialization failed for value " + Span.asString(x))

      for (_ <- 1L to IterationsCount)
        checkValue(Random.nextLong())

      checkValue(Long.MaxValue)
      checkValue(Long.MinValue)
      checkValue(0)
      checkValue(10)
      checkValue(100000)
      checkValue(-10)
      checkValue(-100000)

      success
    }
  }

}
