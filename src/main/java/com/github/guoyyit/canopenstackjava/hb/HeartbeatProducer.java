package com.github.guoyyit.canopenstackjava.hb;

import com.github.guoyyit.canopenstackjava.can.CanBus;
import com.github.guoyyit.canopenstackjava.can.CanFrame;
import com.github.guoyyit.canopenstackjava.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.nmt.NmtStateMachine;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 心跳生产者（0x1017 producer heartbeat time）：
 * 周期在 0x700+nodeId 发送当前 NMT 状态字节；初始化完成时发送 0x00 bootup。
 */
public final class HeartbeatProducer {

    private final int nodeId;
    private final CanBus bus;
    private final NmtStateMachine nmt;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    private long periodMillis;
    private ScheduledFuture<?> task;

    public HeartbeatProducer(int nodeId, CanBus bus, NmtStateMachine nmt,
                             ScheduledExecutorService scheduler, long periodMillis) {
        this.nodeId = nodeId & 0x7F;
        this.bus = bus;
        this.nmt = nmt;
        this.scheduler = scheduler;
        this.periodMillis = periodMillis;
    }

    public void start() {
        synchronized (lock) {
            reschedule();
        }
    }

    public void stop() {
        synchronized (lock) {
            cancel();
        }
    }

    public void setPeriodMillis(long periodMillis) {
        synchronized (lock) {
            this.periodMillis = periodMillis;
            reschedule();
        }
    }

    public long getPeriodMillis() {
        synchronized (lock) {
            return periodMillis;
        }
    }
    /** bootup：数据字节 0x00，仅初始化完成时发送一次。 */
    public void sendBootup() {
        bus.send(new CanFrame(0x700 + nodeId, new byte[]{0x00}, 0));
    }

    public void sendState(NmtState state) {
        if (state.allowsHeartbeat()) {
            bus.send(new CanFrame(0x700 + nodeId, new byte[]{(byte) state.code()}, 0));
        }
    }

    private void reschedule() {
        cancel();
        if (periodMillis <= 0L) {
            return;
        }
        long period = periodMillis;
        task = scheduler.scheduleAtFixedRate(() -> {
            NmtState state = nmt.getState();
            if (state.allowsHeartbeat()) {
                sendState(state);
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    private void cancel() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }
}
