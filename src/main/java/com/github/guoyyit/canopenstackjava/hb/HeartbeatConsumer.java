package com.github.guoyyit.canopenstackjava.hb;

import com.github.guoyyit.canopenstackjava.can.CanBus;
import com.github.guoyyit.canopenstackjava.can.CanFrame;
import com.github.guoyyit.canopenstackjava.can.FrameListener;
import com.github.guoyyit.canopenstackjava.nmt.NmtState;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 心跳消费者 / 节点守护：在守护窗口内未收到对端心跳（含 bootup）即判定超时。
 * 超时只回调一次；收到下一帧后自动恢复守护。
 */
public final class HeartbeatConsumer implements FrameListener {

    /**
     * 守护事件回调。
     */
    public interface Listener {
        /** 守护窗口内未收到心跳。 */
        void heartbeatTimedOut(int nodeId);

        /** 收到心跳 / bootup。bootup 帧状态按 PRE_OPERATIONAL 报告。 */
        void heartbeatReceived(int nodeId, NmtState state);
    }

    private final CanBus bus;
    private final ScheduledExecutorService scheduler;

    private final Map<Integer, Monitor> monitors = new ConcurrentHashMap<>();
    private volatile Listener listener;
    private volatile boolean running;

    public HeartbeatConsumer(CanBus bus, ScheduledExecutorService scheduler) {
        this.bus = bus;
        this.scheduler = scheduler;
    }

    public void start() {
        bus.addListener(this);
        running = true;
    }

    public void stop() {
        running = false;
        bus.removeListener(this);
        monitors.forEach((id, monitor) -> monitor.cancel());
        monitors.clear();
    }

    /** 开始守护某节点，guardWindowMillis 即 consumer heartbeat time / 守护窗口。 */
    public void monitor(int nodeId, long guardWindowMillis) {
        if (guardWindowMillis <= 0L) {
            throw new IllegalArgumentException("guard window must be positive");
        }
        Monitor existing = monitors.remove(nodeId & 0x7F);
        if (existing != null) {
            existing.cancel();
        }
        Monitor monitor = new Monitor(nodeId & 0x7F, guardWindowMillis);
        monitors.put(monitor.nodeId, monitor);
        monitor.arm();
    }

    public void unmonitor(int nodeId) {
        Monitor monitor = monitors.remove(nodeId & 0x7F);
        if (monitor != null) {
            monitor.cancel();
        }
    }

    public Map<Integer, NmtState> lastStates() {
        Map<Integer, NmtState> result = new HashMap<>();
        monitors.forEach((id, monitor) -> {
            if (monitor.lastState != null) {
                result.put(id, monitor.lastState);
            }
        });
        return result;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onFrame(CanFrame frame) {
        if (!running) {
            return;
        }
        int cobId = frame.getCobId();
        if (cobId < 0x701 || cobId > 0x77F) {
            return;
        }
        if (frame.getDlc() < 1) {
            return;
        }
        int nodeId = cobId - 0x700;
        Monitor monitor = monitors.get(nodeId);
        if (monitor == null) {
            return;
        }
        NmtState state = decodeState(frame.dataBytes()[0] & 0xFF);
        monitor.heartbeatReceived(state);
    }

    private static NmtState decodeState(int code) {
        switch (code) {
            case 0x05:
                return NmtState.OPERATIONAL;
            case 0x04:
                return NmtState.STOPPED;
            case 0x00:
            case 0x7F:
            default:
                return NmtState.PRE_OPERATIONAL;
        }
    }

    private final class Monitor {
        private final int nodeId;
        private final long windowMillis;

        private final Object monitorLock = new Object();
        private ScheduledFuture<?> timeoutTask;
        private boolean timedOut;
        private volatile NmtState lastState;

        private Monitor(int nodeId, long windowMillis) {
            this.nodeId = nodeId;
            this.windowMillis = windowMillis;
        }

        private void arm() {
            synchronized (monitorLock) {
                reschedule();
            }
        }

        private void cancel() {
            synchronized (monitorLock) {
                if (timeoutTask != null) {
                    timeoutTask.cancel(false);
                    timeoutTask = null;
                }
            }
        }

        private void heartbeatReceived(NmtState state) {
            synchronized (monitorLock) {
                timedOut = false;
                lastState = state;
                reschedule();
            }
            Listener current = listener;
            if (current != null) {
                current.heartbeatReceived(nodeId, state);
            }
        }

        private void fireTimeout() {
            boolean shouldFire;
            synchronized (monitorLock) {
                shouldFire = !timedOut;
                timedOut = true;
            }
            Listener current = listener;
            if (shouldFire && current != null) {
                current.heartbeatTimedOut(nodeId);
            }
        }

        private void reschedule() {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            timeoutTask = scheduler.schedule(this::fireTimeout, windowMillis, TimeUnit.MILLISECONDS);
        }
    }
}
