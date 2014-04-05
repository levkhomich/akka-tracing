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

import java.util.concurrent.ConcurrentLinkedQueue

import akka.actor.{ActorSystem, ActorRef}
import akka.dispatch._
import com.typesafe.config.Config

/**
 * TracingUnboundedMailbox is the implementation of default unbounded MailboxType
 * enhanced by the ability to trace incoming messages.
 */
class TracingUnboundedMailbox(settings: ActorSystem.Settings, config: Config)
    extends MailboxType with ProducesMessageQueue[UnboundedMailbox.MessageQueue] {

  class TracingQueue(system: ActorSystem) extends ConcurrentLinkedQueue[Envelope] with QueueBasedMessageQueue
      with UnboundedMessageQueueSemantics {

    override def queue = this

    override def dequeue(): Envelope = {
      queue.poll()
    }

    override def enqueue(receiver: ActorRef, handle: Envelope): Unit = {
      handle.message match {
        case ts: TracingSupport =>
          TracingExtension(system).record(ts, receiver.path + " received " + ts)
        case _ =>
      }
      queue.add(handle)
    }
  }

  final override def create(owner: Option[ActorRef], systemOpt: Option[ActorSystem]): MessageQueue =
    systemOpt match {
      case Some(system) =>
        new TracingQueue(system)
      case _ =>
        throw new IllegalArgumentException("No actor system specified")
    }

}
