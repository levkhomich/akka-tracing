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

package org.example.javaapi;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import com.typesafe.config.ConfigFactory;
import org.example.ExternalRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static akka.pattern.Patterns.ask;

public class TraceHierarchy {

    private static String random() {
        return UUID.randomUUID().toString();
    }

    public static void main(String[] args) {
        ActorSystem system = ActorSystem.create("TraceHierarchySystem", ConfigFactory.load("application"));
        ActorRef handler = system.actorOf(Props.create(RequestHandler.class), "requestHandler");

        for (int i = 0; i < 100; i++) {
            Map<String, String> params = new HashMap<>();
            params.put("userAgent", random());
            ask(handler, new ExternalRequest(params, random()), 500);
        }

        system.awaitTermination();
    }

}