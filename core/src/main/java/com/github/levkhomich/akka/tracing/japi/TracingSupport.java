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
    public long tracingId() {
        long a = System.identityHashCode(this);
        long b = hashCode();
        return a << 32 | b & 0xFFFFFFFFL;
    }

    @Deprecated
    @Override
    public BaseTracingSupport asChildOf(BaseTracingSupport parent, TracingExtensionImpl tracer) {
        tracer.createChild(this, parent);
        return this;
    }
}
