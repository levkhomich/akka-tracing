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

package com.github.levkhomich.akka.tracing.pattern

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.{ActorRef, ActorSelection}
import akka.util.Timeout

import com.github.levkhomich.akka.tracing.{BaseTracingSupport, TracingExtensionImpl}


trait TracingAskSupport {

  implicit def ask(actorRef: ActorRef): TracedAskableActorRef =
    new TracedAskableActorRef(actorRef)

  def ask(actorRef: ActorRef, message: BaseTracingSupport)
         (implicit timeout: Timeout, ec: ExecutionContext, trace: TracingExtensionImpl): Future[Any] =
    actorRef ? message

  implicit def ask(actorSelection: ActorSelection): TracedAskableActorSelection =
    new TracedAskableActorSelection(actorSelection)

  def ask(actorSelection: ActorSelection, message: BaseTracingSupport)
         (implicit timeout: Timeout, ec: ExecutionContext, trace: TracingExtensionImpl): Future[Any] =
    actorSelection ? message

}

final class TracedAskableActorRef(val actorRef: ActorRef) extends AnyVal {

  def ask(message: BaseTracingSupport)
         (implicit timeout: Timeout, ec: ExecutionContext, trace: TracingExtensionImpl): Future[Any] = {
    import akka.pattern.{ask => akkaAsk}
    akkaAsk(actorRef, message).transform({ resp =>
      trace.record(message, "response: " + resp)
      trace.finish(message)
      resp
    }, { e =>
      trace.record(message, e)
      trace.finish(message)
      e
    })
  }

  def ?(message: BaseTracingSupport)
       (implicit timeout: Timeout, ec: ExecutionContext, trace: TracingExtensionImpl): Future[Any] =
    ask(message)(timeout, ec, trace)
}


final class TracedAskableActorSelection(val actorSel: ActorSelection) extends AnyVal {

  def ask(message: BaseTracingSupport)
         (implicit timeout: Timeout, ec: ExecutionContext, trace: TracingExtensionImpl): Future[Any] = {
    import akka.pattern.{ask => akkaAsk}
    akkaAsk(actorSel, message).transform({ resp =>
      trace.record(message, "response: " + resp)
      trace.finish(message)
      resp
    }, { e =>
      trace.record(message, e)
      trace.finish(message)
      e
    })
  }

  def ?(message: BaseTracingSupport)
       (implicit timeout: Timeout, ec: ExecutionContext, trace: TracingExtensionImpl): Future[Any] =
    ask(message)(timeout, ec, trace)
}

