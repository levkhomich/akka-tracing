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
import scala.util.{ Random, Success, Failure, Try }

import com.github.levkhomich.akka.tracing.http.TracingHeaders._

/**
 * Case class keeping tracing metadata related to a span.
 * Mainly used in combination with trace.exportMetadata, trace.importMetadata and
 * variety of sampling call. Can be used for interop with external systems using
 * different instrumentation frameworks such as Brave or Finagle.
 */
final case class SpanMetadata(traceId: Long, spanId: Long, parentId: Option[Long], forceSampling: Boolean) {

  /**
   * Serializes metadata to its binary representation.
   *
   * @return metadata binary representation
   */
  def toByteArray: Array[Byte] = {
    val bb = ByteBuffer.allocate(8 * 4)
    bb.putLong(spanId)
    bb.putLong(parentId.getOrElse(traceId))
    bb.putLong(traceId)
    bb.putLong(flags)
    bb.array()
  }

  def flags: Long =
    if (forceSampling) DebugFlag.toByte else 0.toByte
}

object SpanMetadata {

  private[this] val lookup: Array[Array[Char]] = {
    for (b <- Short.MinValue to Short.MaxValue) yield {
      val bb = if (b < 0) b - Short.MinValue * 2 else b
      val s = "%04x".format(bb)
      Array(s.charAt(0), s.charAt(1), s.charAt(2), s.charAt(3))
    }
  }.toArray

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
    if (x == null || x.length == 0) {
      throw new NumberFormatException("Empty span id")
    } else if (x.length > 32) {
      throw new NumberFormatException("Span id is too long: " + x)
    } else if (x.length > 16) {
      idFromString(x.takeRight(16))
    } else {
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
  }

  /**
   * Tries to deserialize SpanMetadata from its binary representation.
   *
   * @param data data to be deserialized
   * @return Some(metadata) in case of successful deserialization and None otherwise
   */
  def fromByteArray(data: Array[Byte]): Option[SpanMetadata] = {
    if (data == null || data.length != 32)
      None
    else
      try {
        val bb = ByteBuffer.wrap(data)
        val spanId = bb.getLong
        val rawParentId = bb.getLong
        val traceId = bb.getLong
        val parentId = if (rawParentId == spanId || rawParentId == traceId) None else Some(rawParentId)
        val flags = bb.getLong
        val forceSampling = (flags & DebugFlag) == DebugFlag
        if (bb.hasRemaining)
          None
        else
          Some(SpanMetadata(traceId, spanId, parentId, forceSampling))
      } catch {
        case _: BufferUnderflowException =>
          None
      }
  }

  private[tracing] def extractSpan(headers: String => Option[String], requireTraceId: Boolean): Either[String, Option[SpanMetadata]] = {
    def headerLongValue(name: String): Either[String, Option[Long]] =
      Try(headers(name).map(SpanMetadata.idFromString)) match {
        case Failure(e) =>
          Left(name)
        case Success(v) =>
          Right(v)
      }
    def spanId: Long =
      headerLongValue(SpanId).right.toOption.flatten.getOrElse(Random.nextLong)

    // debug flag forces sampling (see http://git.io/hdEVug)
    val maybeForceSampling =
      headers(Sampled).map(_.toLowerCase) match {
        case Some("0") | Some("false") =>
          Some(false)
        case Some("1") | Some("true") =>
          Some(true)
        case _ =>
          headers(Flags).flatMap(flags =>
            Try((java.lang.Long.parseLong(flags) & DebugFlag) == DebugFlag).toOption.filter(v => v))
      }

    maybeForceSampling match {
      case Some(false) =>
        Right(None)
      case _ =>
        val forceSampling = maybeForceSampling.getOrElse(false)
        headerLongValue(TraceId).right.map({
          case Some(traceId) =>
            headerLongValue(ParentSpanId).right.map { parentId =>
              Some(SpanMetadata(traceId, spanId, parentId, forceSampling))
            }
          //            val parentId = headerLongValue(ParentSpanId).right.getOrElse(None)
          //            Right(Some(SpanMetadata(traceId, spanId, parentId, forceSampling)))
          case _ if requireTraceId =>
            Right(None)
          case _ =>
            Right(Some(SpanMetadata(Random.nextLong, spanId, None, forceSampling)))
        }).joinRight
    }
  }

}
