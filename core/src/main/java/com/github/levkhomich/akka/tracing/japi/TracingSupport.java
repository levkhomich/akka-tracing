package com.github.levkhomich.akka.tracing.japi;

import com.github.levkhomich.akka.tracing.BaseTracingSupport;
import com.github.levkhomich.akka.tracing.TracingExtensionImpl;
import scala.Option;

import java.util.Random;

public abstract class TracingSupport implements BaseTracingSupport {

    private long spanId = new Random().nextLong();
    private Option<Object> traceId = Option.empty();
    private Option<Object> parentId = Option.empty();

    @Override
    public long spanId() {
        return spanId;
    }

    @Override
    public Option<Object> traceId() {
        return traceId;
    }

    @Override
    public void setTraceId(Option<Object> newTraceId) {
        traceId = newTraceId;
    }

    @Override
    public Option<Object> parentId() {
        return parentId;
    }

    @Override
    public BaseTracingSupport asChildOf(BaseTracingSupport ts, TracingExtensionImpl tracer) {
        tracer.createChildSpan(spanId, ts);
        parentId = scala.Option.apply((Object) ts.spanId());
        traceId = ts.traceId();
        return this;
    }
}
