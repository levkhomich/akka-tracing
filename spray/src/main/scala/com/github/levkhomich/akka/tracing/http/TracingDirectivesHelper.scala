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

import scala.util.{Random, Success, Try}
import spray.http.HttpMessage

import com.github.levkhomich.akka.tracing.Span

private[http] object TracingDirectivesHelper {

  import TracingHeaders._

  private[this] val DebugFlag = 1L

  def extractSpan(message: HttpMessage): Try[Option[Span]] = {
    def headerStringValue(name: String): Option[String] =
      message.headers.find(_.name == name).map(_.value)
    def headerLongValue(name: String): Try[Option[Long]] =
      Try(headerStringValue(name).map(Span.fromString))
    def isFlagSet(v: String, flag: Long): Boolean =
      (java.lang.Long.parseLong(v) & flag) == flag
    // debug flag forces sampling (see http://git.io/hdEVug)
    def forceSampling: Boolean =
      headerStringValue(Flags).exists(isFlagSet(_, DebugFlag)) ||
        headerStringValue(Sampled).filter(_ == "true").isDefined
    def spanId: Long =
      headerLongValue(SpanId).toOption.flatten.getOrElse(Random.nextLong)

    headerLongValue(TraceId).flatMap {
      case Some(traceId) =>
        headerLongValue(ParentSpanId).map { parentId =>
          Some(Span(traceId, spanId, parentId, forceSampling))
        }
      case None if forceSampling =>
        Success(Some(Span(Random.nextLong, spanId, None, true)))
      case _ =>
        Success(None)
    }
  }

}

