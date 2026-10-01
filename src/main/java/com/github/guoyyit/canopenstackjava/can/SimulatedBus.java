package com.github.guoyyit.canopenstackjava.can;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGeneratorFactory;

import java.util.random.RandomGenerator;

/** 支持丢帧 / 重复 / 延迟注入的模拟总线。 */
public final class SimulatedBus implements CanBus {

    private final CopyOnWriteArrayList<FrameListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicLong sequenceGenerator = new AtomicLong(0);
    private final Object randomLock = new Object();

    private volatile FaultProfile profile;
    private RandomGenerator random;

    public SimulatedBus(FaultProfile profile, ScheduledExecutorService scheduler) {
        this.profile = profile == null ? FaultProfile.noFaults() : profile;
        this.scheduler = scheduler;
        this.random = RandomGeneratorFactory.of("Random").create(this.profile.getSeed());
    }

    @Override
    public void send(final CanFrame incoming) {
        FaultProfile current = this.profile;
        long seq = sequenceGenerator.incrementAndGet();
        final CanFrame frame = incoming.getSequence() > 0 ? incoming
                : new CanFrame(incoming.getCobId(), incoming.dataBytes(), seq);

        boolean drop;
        boolean duplicate;
        synchronized (randomLock) {
            drop = current.getDropProbability() > 0.0
                    && random.nextDouble() < current.getDropProbability();
            duplicate = !drop && current.getDuplicateProbability() > 0.0
                    && random.nextDouble() < current.getDuplicateProbability();
        }
        if (drop) {
            return;
        }
        if (current.getDelayMillis() > 0L) {
            scheduler.schedule(() -> deliver(frame), current.getDelayMillis(), TimeUnit.MILLISECONDS);
        } else {
            deliver(frame);
        }
        if (duplicate) {
            if (current.getDelayMillis() > 0L) {
                scheduler.schedule(() -> deliver(frame), current.getDelayMillis() + 1L, TimeUnit.MILLISECONDS);
            } else {
                deliver(frame);
            }
        }
    }

    @Override
    public void addListener(FrameListener listener) {
        listeners.addIfAbsent(listener);
    }

    @Override
    public void removeListener(FrameListener listener) {
        listeners.remove(listener);
    }

    public void setFaultProfile(FaultProfile profile) {
        FaultProfile next = profile == null ? FaultProfile.noFaults() : profile;
        synchronized (randomLock) {
            this.profile = next;
            this.random = RandomGeneratorFactory.of("Random").create(next.getSeed());
        }
    }

    private void deliver(CanFrame frame) {
        FrameListener[] snapshot = listeners.toArray(new FrameListener[0]);
        for (FrameListener listener : snapshot) {
            try {
                listener.onFrame(frame);
            } catch (RuntimeException ignored) {
                // 单个监听器异常不得影响其他节点接收，避免总线投递中断造成假死。
            }
        }
    }
}
