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

package org.example

import akka.actor.{Actor, Props, ActorSystem}
import akka.io.IO
import spray.can.Http
import spray.http._
import spray.http.ContentTypes._
import spray.httpx.unmarshalling.Unmarshaller
import spray.routing.HttpService
import scala.xml.NodeSeq

import com.github.levkhomich.akka.tracing.{ActorTracing, TracingSupport, TracingDirectives}

object SprayDirectives extends App {
  implicit val system = ActorSystem("akka-tracing-spray-directives")
  val service = system.actorOf(Props[SprayDirectivesServiceActor], "spray-directives-service")
  IO(Http) ! Http.Bind(service, "localhost", port = 8080)
}

case class RootRequest(data: String) extends TracingSupport

object RootRequest {
  implicit val RootRequestUnmarshaller =
    Unmarshaller[RootRequest](ContentTypeRange.*) {
      case HttpEntity.NonEmpty(contentType, data) ⇒
        RootRequest.apply(data.toString)

      case HttpEntity.Empty ⇒
        RootRequest.apply("")
    }
}

class SprayDirectivesServiceActor extends Actor with ActorTracing with HttpService with TracingDirectives {

  import RootRequest._

  implicit def executionContext = actorRefFactory.dispatcher

  def actorRefFactory = context
  def receive = runRoute(route)

  def process(r: RootRequest): String =
    r.toString

  val route = {
    get {
      pathSingleSlash {
        tracedHandleWith {
          process
        }
      }
    }
  }
}