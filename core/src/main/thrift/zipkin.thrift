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

include "scribe.thrift"

namespace java com.github.levkhomich.akka.tracing.thrift

const string CLIENT_SEND = "cs"
const string CLIENT_RECV = "cr"
const string SERVER_SEND = "ss"
const string SERVER_RECV = "sr"
const string SERVER_ADDR = "sa"
const string CLIENT_ADDR = "ca"

struct Endpoint {
  1: i32 ipv4,
  2: i16 port,
  3: string service_name
}

struct Annotation {
  1: i64 timestamp,
  2: string value,
  3: optional Endpoint host,
  4: optional i32 duration
}

enum AnnotationType {
  BOOL,
  BYTES,
  I16,
  I32,
  I64,
  DOUBLE,
  STRING
}

struct BinaryAnnotation {
  1: string key,
  2: binary value,
  3: AnnotationType annotation_type,
  4: optional Endpoint host
}

struct Span {
  1: i64 trace_id,
  3: string name,
  4: i64 id,
  5: optional i64 parent_id,
  6: list<Annotation> annotations,
  8: list<BinaryAnnotation> binary_annotations,
  9: optional bool debug = 0
}

