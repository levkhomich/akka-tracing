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

package com.github.levkhomich.akka.tracing.thrift

import java.util
import scala.util.Random

import org.specs2.mutable.Specification

class TReusableTransportSpec extends Specification {

  sequential

  val transport = new TReusableTransport()

  "TReusableTransport" should {

    val DatasetSize = 100000
    val WriteBatchSize = 73
    val ReadBatchSize = 91
    val dataset = new Array[Byte](DatasetSize)
    (0 until DatasetSize).foreach(dataset(_) = Random.nextInt.toByte)

    "not support open() and close()" in {
      transport.open() must throwA[UnsupportedOperationException]
      transport.close() must throwA[UnsupportedOperationException]
      transport.isOpen mustEqual true
    }

    "support write()" in {
      var offset = 0
      while (offset < DatasetSize) {
        transport.write(dataset, offset, WriteBatchSize min (DatasetSize - offset))
        offset += WriteBatchSize
      }
      transport.length mustEqual DatasetSize
    }

    "support read()" in {
      val buffer = new Array[Byte](ReadBatchSize)
      var offset = 0
      while (offset < DatasetSize) {
        val read = transport.read(buffer, 0, ReadBatchSize min (DatasetSize - offset))
        for (i <- 0 until read) {
          dataset(i + offset) mustEqual buffer(i)
        }
        offset += ReadBatchSize
      }
      util.Arrays.equals(transport.getArray.take(DatasetSize), dataset) mustEqual true
    }

    "support resetting" in {
      transport.reset()
      transport.length mustEqual 0
    }
  }

}
