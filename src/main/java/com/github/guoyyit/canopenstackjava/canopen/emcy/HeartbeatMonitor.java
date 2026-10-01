package com.github.guoyyit.canopenstackjava.canopen.emcy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * 心跳消费者/守护：在守护窗口（生产者周期 + 守护余量）内未收到对端心跳则检出超时。
 * 超时事件只边沿触发一次；心跳恢复后自动清除超时标志。
 */
public class HeartbeatMonitor {

    private final int localNodeId;
    private final int monitoredNodeId;
    private final long guardWindowMillis;
    private final BiConsumer<Integer, Long> timeoutCallback;

    private final AtomicBoolean timedOut = new AtomicBoolean(false);
    private long lastSeenMillis = Long.MIN_VALUE;

    public HeartbeatMonitor(int localNodeId, int monitoredNodeId, long guardWindowMillis,
                            BiConsumer<Integer, Long> timeoutCallback) {
        if (guardWindowMillis <= 0L) {
            throw new IllegalArgumentException("guard window must be positive");
        }
        this.localNodeId = localNodeId;
        this.monitoredNodeId = monitoredNodeId;
        this.guardWindowMillis = guardWindowMillis;
        this.timeoutCallback = timeoutCallback;
    }

    public void onHeartbeat(int nodeId, long nowMillis) {
        if (nodeId != monitoredNodeId) {
            return;
        }
        lastSeenMillis = nowMillis;
        timedOut.set(false);
    }

    public void onTick(long nowMillis) {
        if (lastSeenMillis == Long.MIN_VALUE) {
            return;
        }
        boolean expired = nowMillis - lastSeenMillis > guardWindowMillis;
        if (expired && timedOut.compareAndSet(false, true)) {
            timeoutCallback.accept(monitoredNodeId, nowMillis - lastSeenMillis);
        }
    }

    public boolean isTimedOut() {
        return timedOut.get();
    }

    public int getMonitoredNodeId() {
        return monitoredNodeId;
    }

    public int getLocalNodeId() {
        return localNodeId;
    }
}
