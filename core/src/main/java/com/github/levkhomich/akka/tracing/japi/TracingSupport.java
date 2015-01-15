package com.github.levkhomich.akka.tracing.japi;

import java.io.Serializable;

import com.github.levkhomich.akka.tracing.BaseTracingSupport;
import com.github.levkhomich.akka.tracing.TracingExtensionImpl;

public abstract class TracingSupport implements BaseTracingSupport, Serializable {

    private static final long serialVersionUID = 1L;

    @Override
    public String spanName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public BaseTracingSupport asChildOf(BaseTracingSupport ts, TracingExtensionImpl tracer) {
        tracer.createChildSpan(tracingId(), ts.tracingId(), spanName());
        return this;
    }
}
