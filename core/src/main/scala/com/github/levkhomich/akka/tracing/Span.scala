package com.github.levkhomich.akka.tracing

import java.io.{ByteArrayInputStream, DataInputStream}

private[tracing] final case class Span(traceId: Option[Long], spanId: Long, parentId: Option[Long]) extends BaseTracingSupport {
  override private[tracing] def setTraceId(newTraceId: Option[Long]): Unit = throw new UnsupportedOperationException
  override def asChildOf(ts: BaseTracingSupport)(implicit tracer: TracingExtensionImpl): this.type = this
  override private[tracing] def msgId: Long = spanId
}

private[tracing] object Span {

  private val lookup: Array[Array[Char]] = (
    for (b <- Short.MinValue to Short.MaxValue) yield {
      val bb = if (b < 0) b - Short.MinValue * 2 else b
      val s = "%04x".format(bb)
      Array(s.charAt(0), s.charAt(1), s.charAt(2), s.charAt(3))
    }
    ).toArray

  private def asChars(b: Long) =
    lookup((b & 0xffff).toShort - Short.MinValue)

  def asString(x: Long): String = {
    val b = new StringBuilder(16)
    b.appendAll(asChars(x >> 48))
    b.appendAll(asChars(x >> 32))
    b.appendAll(asChars(x >> 16))
    b.appendAll(asChars(x))
    b.toString()
  }

  def fromString(s: String): Long = {
    if (s.length > 16)
      throw new NumberFormatException("String is longer than 16 chars")
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
