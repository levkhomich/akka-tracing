package com.github.levkhomich.akka.tracing

import java.util
import java.util.Map.Entry

private[tracing] class MetadataCache(maxSize: Int) extends util.LinkedHashMap[Long, SpanMetadata] {
  override def removeEldestEntry(eldest: Entry[Long, SpanMetadata]): Boolean =
    size > maxSize
}