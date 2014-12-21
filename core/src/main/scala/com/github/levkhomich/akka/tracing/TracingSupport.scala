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

import scala.util.Random

trait BaseTracingSupport extends Any {

  // use $ to provide better compatibility with spray-json
  private[tracing] def $spanId: Long
  private[tracing] def $traceId: Option[Long]
  private[tracing] def $parentId: Option[Long]

  protected[tracing] def spanName: String

  def asChildOf(ts: BaseTracingSupport)(implicit tracer: TracingExtensionImpl): BaseTracingSupport

  private[tracing] def sample(): Unit
  private[tracing] def isSampled: Boolean
}

/**
 * Trait to be mixed with messages that should support tracing.
 */
trait TracingSupport extends BaseTracingSupport with Serializable {

  private[tracing] var $spanId = Random.nextLong()
  private[tracing] var $traceId: Option[Long] = None
  private[tracing] var $parentId: Option[Long] = None

  override def spanName: String =
    this.getClass.getSimpleName

  /**
   * Declares message as a child of another message.
   * @param ts parent message
   * @return child message with required tracing headers
   */
  override def asChildOf(ts: BaseTracingSupport)(implicit tracer: TracingExtensionImpl): this.type = {
    require(!isSampled)
    tracer.createChildSpan($spanId, ts, spanName)
    $parentId = Some(ts.$spanId)
    $traceId = ts.$traceId
    this
  }

  override private[tracing] def sample(): Unit = {
    if ($traceId.isEmpty)
      $traceId = Some(Random.nextLong())
  }

  @inline override private[tracing] def isSampled: Boolean = {
    $traceId.isDefined
  }

  private[tracing] def init(spanId: Long, traceId: Long, parentId: Option[Long]): Unit = {
    require(!isSampled)
    this.$spanId = spanId
    this.$traceId = Some(traceId)
    this.$parentId = parentId
  }

}

class ResponseTracingSupport[T](val msg: T) extends AnyVal {

  /**
   * Declares message as a response to another message.
   * @param request parent message
   * @return unchanged message
   */
  def asResponseTo(request: BaseTracingSupport)(implicit trace: TracingExtensionImpl): T = {
    trace.record(request, "response: " + msg)
    trace.finish(request)
    msg
  }
}

