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

import java.io.{ ByteArrayInputStream, DataInputStream }
import java.nio.{ BufferUnderflowException, ByteBuffer }

final case class SpanMetadata(traceId: Long, spanId: Long, parentId: Option[Long], forceSampling: Boolean) {
  def toByteArray: Array[Byte] = {
    val bb = ByteBuffer.allocate(8 + 8 + 1 + parentId.fold(0)(_ => 8) + 1)
    bb.putLong(traceId)
    bb.putLong(spanId)
    bb.put(if (parentId.isDefined) 1.toByte else 0.toByte)
    parentId.foreach(id =>
      bb.putLong(id)
    )
    bb.put(if (forceSampling) 1.toByte else 0.toByte)
    bb.array()
  }
}

object SpanMetadata {

  private[this] val lookup: Array[Array[Char]] = (
    for (b <- Short.MinValue to Short.MaxValue) yield {
      val bb = if (b < 0) b - Short.MinValue * 2 else b
      val s = "%04x".format(bb)
      Array(s.charAt(0), s.charAt(1), s.charAt(2), s.charAt(3))
    }
  ).toArray

  private[this] def asChars(b: Long) =
    lookup((b & 0xffff).toShort - Short.MinValue)

  private[tracing] def idToString(x: Long): String = {
    val b = new StringBuilder(16)
    b.appendAll(asChars(x >> 48))
    b.appendAll(asChars(x >> 32))
    b.appendAll(asChars(x >> 16))
    b.appendAll(asChars(x))
    b.toString()
  }

  private[tracing] def idFromString(x: String): Long = {
    if (x == null || x.length == 0 || x.length > 16)
      throw new NumberFormatException("Invalid span id string: " + x)
    val s =
      if (x.length % 2 == 0) x
      else "0" + x
    val bytes = new Array[Byte](8)
    val start = 7 - (s.length + 1) / 2
    (s.length until 0 by -2).foreach {
      i =>
        val x = Integer.parseInt(s.substring(i - 2, i), 16).toByte
        bytes.update(start + i / 2, x)
    }
    new DataInputStream(new ByteArrayInputStream(bytes)).readLong
  }

  def fromByteArray(data: Array[Byte]): Option[SpanMetadata] = {
    if (data == null || data.length != 18 && data.length != 26)
      None
    else
      try {
        val bb = ByteBuffer.wrap(data)
        val traceId = bb.getLong
        val spanId = bb.getLong
        val parentId =
          if (bb.get == 1)
            Some(bb.getLong)
          else
            None
        val forceSampling = bb.get == 1
        if (bb.hasRemaining)
          None
        else
          Some(SpanMetadata(traceId, spanId, parentId, forceSampling))
      } catch {
        case _: BufferUnderflowException =>
          None
      }
  }
}
