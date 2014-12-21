package com.github.levkhomich.akka.tracing.japi;

import java.util.Random;
import java.io.Serializable;
import scala.Option;

import com.github.levkhomich.akka.tracing.BaseTracingSupport;
import com.github.levkhomich.akka.tracing.TracingExtensionImpl;

public abstract class TracingSupport implements BaseTracingSupport, Serializable {

    private long spanId = new Random().nextLong();
    private Option<Object> traceId = Option.empty();
    private Option<Object> parentId = Option.empty();

    @Override
    public long $spanId() {
        return spanId;
    }

    @Override
    public Option<Object> $traceId() {
        return traceId;
    }

    @Override
    public void sample() {
        traceId = scala.Option.apply((Object) new Random().nextLong());
    }

    @Override
    public boolean isSampled() {
        return traceId.isDefined();
    }

    @Override
    public Option<Object> $parentId() {
        return parentId;
    }

    @Override
    public String spanName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public BaseTracingSupport asChildOf(BaseTracingSupport ts, TracingExtensionImpl tracer) {
        tracer.createChildSpan(spanId, ts, spanName());
        parentId = scala.Option.apply((Object) ts.$spanId());
        traceId = ts.$traceId();
        return this;
    }
}
