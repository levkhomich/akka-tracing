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

import com.github.levkhomich.akka.tracing.japi.TracingSupport;

public class InternalRequest extends TracingSupport {
    private final String payload;

    public String getPayload() {
        return payload;
    }

    public InternalRequest(String payload) {
        this.payload = payload;
    }
}
