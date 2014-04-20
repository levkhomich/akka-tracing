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

import java.util.Map;

public class ExternalRequest extends TracingSupport {
    private final Map<String, String> headers;
    private final String payload;

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getPayload() {
        return payload;
    }

    public ExternalRequest(Map<String, String> headers, String payload) {
        this.headers = headers;
        this.payload = payload;
    }
}

