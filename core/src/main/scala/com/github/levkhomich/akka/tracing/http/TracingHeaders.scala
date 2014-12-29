package com.github.levkhomich.akka.tracing.http

// see https://github.com/twitter/finagle/blob/master/finagle-http/src/main/scala/com/twitter/finagle/http/Codec.scala
object TracingHeaders {
  val TraceId = "X-B3-TraceId"
  val SpanId = "X-B3-SpanId"
  val ParentSpanId = "X-B3-ParentSpanId"
  val Sampled = "X-B3-Sampled"
  val Flags = "X-B3-Flags"

  private[tracing] val DebugFlag = 1L

  private[tracing] val All = Seq(TraceId, SpanId, ParentSpanId, Sampled, Flags)
}
