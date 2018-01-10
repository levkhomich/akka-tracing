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

class PerformanceSpec extends Specification with TracingTestCommons
  with NonCIEnvironmentFilter with TracingTestActorSystem with MockCollector {

  override val sampleRate = 10

  sequential

  "TracingExtension" should {

    val ExpectedMPS = 70000
    val TestMPS = ExpectedMPS + ExpectedMPS / 5
    val BenchmarkDuration = 10 // seconds

    s"process more than $ExpectedMPS messages per second at sample rate $sampleRate" inNonCIEnvironment {
      val SpanCount = TestMPS * BenchmarkDuration

      val RandomLong = Random.nextLong()
      val RandomString = UUID.randomUUID().toString + "-" + UUID.randomUUID().toString

      val startTime = System.currentTimeMillis()
      for (i <- 1 to SpanCount) {
        val msg = nextRandomMessage
        trace.sample(msg, "test")
        trace.recordKeyValue(msg, "keyLong", RandomLong)
        trace.recordKeyValue(msg, "keyString", RandomString)
        trace.record(msg, TracingAnnotations.ServerSend)
        if (i % 100 == 0) {
          val timeSpent = System.currentTimeMillis - startTime
          val expectedTimeSpent = i * 1000 / TestMPS
          val delta = expectedTimeSpent - timeSpent
          if (delta > 0)
            Thread.sleep(delta)
        }
      }
      val processingTime = System.currentTimeMillis() - startTime
      val expectedCount = SpanCount / sampleRate
      val actualCount = getSpanCount

      val spansPerSecond = actualCount * 1000 / processingTime
      println(s"benchmark result: $spansPerSecond SPS, " +
        s"${100 - actualCount * 100 / expectedCount}% dropped by backpressure")

      spansPerSecond * sampleRate must beGreaterThanOrEqualTo(ExpectedMPS.toLong)
    }

    s"minimize delay before span metadata is available" inNonCIEnvironment {
      def run(): (Long, Long) = {
        val msg = nextRandomMessage
        trace.sample(msg, "test", force = true)
        val start = System.nanoTime()

        var failedExports = 0
        while (trace.exportMetadata(msg).isEmpty)
          failedExports += 1

        val time = System.nanoTime() - start
        trace.flush(msg)
        time -> failedExports
      }

      val (time, failures) = (1 to ExpectedMPS).map(_ => run()).foldLeft(0L -> 0L) {
        case ((zt, zf), (t, f)) => (zt + t) -> (zf + f)
      }

      println(s"benchmark result: ${time / ExpectedMPS} ns metadata availability, $failures retries")

      failures mustEqual 0
    }

  }

  step(shutdown())

  "Span" should {

    val IterationsCount = 5000000L

    "serialize faster than naive implementation" inNonCIEnvironment {
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
      benchmark(SpanMetadata.idToString)
      benchmark(naiveLongToString)

      val originalCPS = benchmark(SpanMetadata.idToString)
      val naiveCPS = benchmark(naiveLongToString)
      val percentDelta = originalCPS * 100 / naiveCPS - 100
      println(s"spanId serialization performance delta: $percentDelta%")
      percentDelta must beGreaterThan(-10L)
    }

  }

}
