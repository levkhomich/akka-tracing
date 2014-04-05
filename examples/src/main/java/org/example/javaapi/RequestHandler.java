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
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.japi.JavaPartialFunction;
import com.github.levkhomich.akka.tracing.TracingExtension;
import com.github.levkhomich.akka.tracing.TracingExtensionImpl;
import org.example.ExternalRequest;
import org.example.ExternalResponse;
import org.example.InternalRequest;
import org.example.InternalResponse;
import scala.concurrent.Future;

import static akka.pattern.Patterns.ask;
import static akka.pattern.Patterns.pipe;


public class RequestHandler extends UntypedActor {

    TracingExtensionImpl trace = (TracingExtensionImpl) TracingExtension.apply(context().system());
    final ActorRef child = getContext().actorOf(Props.create(DelegateActor.class));

    public void onReceive(Object message) throws Exception {
        if (message instanceof ExternalRequest) {
            final ExternalRequest msg = (ExternalRequest) message;
            // notify tracing extension about external request to be sampled and traced
            trace.sample(msg);
            // name service processing request
            trace.recordRPCName(msg, this.getClass().getSimpleName());

            // add info about request headers to trace
            for (String key : msg.headers().keySet()) {
                trace.recordKeyValue(msg, key, msg.headers().get(key));
            }

            InternalRequest request = new InternalRequest(msg.payload());

            Future<Object> f = ask(child, request.asChildOf(msg, trace), 500).recover(new JavaPartialFunction<Throwable, Object>() {
                @Override
                public Object apply(Throwable e, boolean isCheck) throws Exception {
                    if (isCheck) return null;
                    // trace exception
                    trace.record(msg, e.toString());
                    return new InternalResponse(500, "");
                }
            }, context().dispatcher());

            f.onSuccess(new JavaPartialFunction<Object, Object>() {
                @Override
                public Object apply(Object intMessage, boolean isCheck) throws Exception {
                    if (intMessage instanceof InternalResponse) {
                        if (isCheck) return null;
                        InternalResponse intResponse = (InternalResponse) intMessage;
                        ExternalResponse response = new ExternalResponse(intResponse.responseCode(), intResponse.toString() + '!');
                        // close trace
                        trace.recordServerSend(msg);
                        return response;
                    } else {
                        throw noMatch();
                    }
                }
            }, context().dispatcher());

            pipe(f, context().dispatcher()).to(sender());
        } else {
            unhandled(message);
        }
    }
}
