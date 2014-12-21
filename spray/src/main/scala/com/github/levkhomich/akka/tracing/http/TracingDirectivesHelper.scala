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

package com.github.levkhomich.akka.tracing.http

import scala.util.Random
import spray.http.HttpMessage
import com.github.levkhomich.akka.tracing.Span

private[http] object TracingDirectivesHelper {

  import TracingHeaders._

  private[this] val DebugFlag = 1L

  def headerByName(message: HttpMessage, name: String): Option[String] =
    message.headers.find(_.name == name).map(_.value)

  def extractSpan(message: HttpMessage): Option[Span] = {
    def extractSpanId: Long =
      headerByName(message, SpanId).map(Span.fromString).getOrElse(Random.nextLong)
    def isFlagSet(v: String, flag: Long): Boolean =
      (java.lang.Long.parseLong(v) & flag) == flag

    val traceIdOpt = headerByName(message, TraceId)
    // debug flag forces sampling (see http://git.io/hdEVug)
    val forceSampling =
      if (headerByName(message, Flags).map(isFlagSet(_, DebugFlag)) == Some(true))
        true
      else
        headerByName(message, Sampled).map(_ == "true").getOrElse(false)

    try {
      traceIdOpt.map(traceId =>
        Span(
          Some(Span.fromString(traceId)),
          extractSpanId,
          headerByName(message, ParentSpanId).map(Span.fromString),
          forceSampling
        )
      ).orElse(
        if (forceSampling)
          Some(Span(
            Some(Random.nextLong),
            extractSpanId,
            None,
            forceSampling
          ))
        else
          None
      )
    } catch {
      case e: NumberFormatException =>
        None
    }
  }

}

