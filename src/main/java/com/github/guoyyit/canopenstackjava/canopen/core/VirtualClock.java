package com.github.guoyyit.canopenstackjava.canopen.core;

/**
 * 时间源。虚拟模式下时间由外部步进推进，保证测试可复现；真实模式下使用系统墙钟。
 */
public class VirtualClock {

    private final boolean real;
    private long nowMillis;

    public VirtualClock() {
        this(true);
    }

    public VirtualClock(boolean real) {
        this.real = real;
        this.nowMillis = real ? System.currentTimeMillis() : 0L;
    }

    public long nowMillis() {
        if (real) {
            return System.currentTimeMillis();
        }
        return nowMillis;
    }

    public boolean isVirtual() {
        return !real;
    }

    public void advance(long deltaMillis) {
        if (deltaMillis < 0) {
            throw new IllegalArgumentException("deltaMillis must be non-negative");
        }
        if (!real) {
            nowMillis += deltaMillis;
        }
    }
}
