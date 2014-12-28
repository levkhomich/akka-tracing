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

import java.util.UUID
import scala.util.Random

import org.specs2.mutable.Specification

class PerformanceSpec extends Specification with TracingTestCommons with TracingTestActorSystem with MockCollector {

  override val sampleRate = 10

  sequential

  "TracingExtension" should {

    val ExpectedTPS = 70000

    s"process more than $ExpectedTPS traces per second using single thread" in {
      val SpanCount = ExpectedTPS * 4

      val startingTime = System.currentTimeMillis()
      for (_ <- 1 to SpanCount) {
        val msg = nextRandomMessage
        trace.sample(msg, "test")
        trace.recordKeyValue(msg, "keyLong", Random.nextLong())
        trace.recordKeyValue(msg, "keyString", UUID.randomUUID().toString + "-" + UUID.randomUUID().toString + "-")
        trace.finish(msg)
      }
      val tracesPerSecond = SpanCount * 1000 / (System.currentTimeMillis() - startingTime)
      Thread.sleep(10000)
      println(s"benchmark: TPS = $tracesPerSecond")

      tracesPerSecond must beGreaterThan(ExpectedTPS.toLong)
      results.size() must beEqualTo(SpanCount / sampleRate)
    }
  }

  step(shutdown())

  "Span" should {

    val IterationsCount = 5000000L

    "serialize faster than naive implementation" in {
      def naiveLongToString(x: Long): String = {
        val s = java.lang.Long.toHexString(x)
        "0" * (16 - s.length) + s
      }

      def benchmark(f: Long => String): Long = {
        val nanos = System.nanoTime
        for (i <- 1L to IterationsCount) {
          val _ = f(i)
        }
        IterationsCount * 1000000000 / (System.nanoTime - nanos)
      }

      // warm up
      benchmark(Span.asString)
      benchmark(naiveLongToString)

      val originalCPS = benchmark(Span.asString)
      val naiveCPS = benchmark(naiveLongToString)
      val percentDelta = originalCPS * 100 / naiveCPS - 100
      println(s"benchmark: spanId serialization performance delta = $percentDelta%")
      percentDelta must beGreaterThan(-10L)
    }

  }

}
