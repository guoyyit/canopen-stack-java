package com.github.guoyyit.canopenstackjava.canopen.core;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 模拟 CAN 总线实现：广播分发到所有已挂载节点（节点按 COB-ID 自行过滤），
 * 支持基于固定种子的丢帧/重复/延迟故障注入。
 */
public class SimulatedCanBus implements CanBus {

    private final VirtualClock clock;
    private final FaultInjection faults;

    private final TreeMap<Integer, CanBusListener> listeners = new TreeMap<>();
    private final Deque<CanFrame> ready = new ArrayDeque<>();
    private final PriorityQueue<PendingDelivery> delayed = new PriorityQueue<>();
    private final ReentrantLock lock = new ReentrantLock();

    private boolean pumping = false;
    private long sequence = 0L;

    public SimulatedCanBus(VirtualClock clock, FaultInjection faults) {
        this.clock = clock;
        this.faults = faults;
    }

    @Override
    public void attach(int nodeId, CanBusListener listener) {
        lock.lock();
        try {
            listeners.put(nodeId, listener);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void detach(int nodeId) {
        lock.lock();
        try {
            listeners.remove(nodeId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void send(int senderNodeId, CanFrame frame) {
        lock.lock();
        try {
            // 随机数调用顺序固定：丢帧 -> 重复 -> 延迟，保证相同种子与相同发送序列下行为可复现。
            boolean dropped = faults.shouldDrop();
            boolean duplicate = faults.shouldDuplicate();
            long delay = faults.nextDelayMillis();
            if (dropped) {
                return;
            }
            long deliveryTime = clock.nowMillis() + delay;
            if (deliveryTime <= clock.nowMillis()) {
                ready.addLast(frame);
                if (duplicate) {
                    ready.addLast(new CanFrame(frame.getId(), frame.getData()));
                }
            } else {
                delayed.add(new PendingDelivery(deliveryTime, sequence++, frame));
                if (duplicate) {
                    delayed.add(new PendingDelivery(deliveryTime, sequence++,
                            new CanFrame(frame.getId(), frame.getData())));
                }
            }
            pump();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void runExclusively(Runnable action) {
        lock.lock();
        try {
            action.run();
            pump();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 释放所有到期的延迟帧。由网络统一时钟步进驱动（虚拟模式）或实时调度器驱动。
     */
    public void dispatchPending(long nowMillis) {
        lock.lock();
        try {
            while (!delayed.isEmpty() && delayed.peek().deliveryTime <= nowMillis) {
                ready.addLast(delayed.poll().frame);
            }
            pump();
        } finally {
            lock.unlock();
        }
    }

    private void pump() {
        if (pumping) {
            // 监听器回调中嵌套发送时，外层泵会继续排空队列，无需重入。
            return;
        }
        pumping = true;
        try {
            CanFrame frame;
            while ((frame = ready.pollFirst()) != null) {
                deliver(frame);
            }
        } finally {
            pumping = false;
        }
    }

    private void deliver(CanFrame frame) {
        List<Map.Entry<Integer, CanBusListener>> snapshot = new ArrayList<>(listeners.entrySet());
        for (Map.Entry<Integer, CanBusListener> entry : snapshot) {
            try {
                entry.getValue().onFrame(frame);
            } catch (RuntimeException ex) {
                // 单个监听器异常不得阻断总线上其他节点的接收。
                System.getLogger(SimulatedCanBus.class.getName())
                        .log(System.Logger.Level.WARNING,
                                "node " + entry.getKey() + " frame handler failed", ex);
            }
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            listeners.clear();
            ready.clear();
            delayed.clear();
        } finally {
            lock.unlock();
        }
    }

    private static final class PendingDelivery implements Comparable<PendingDelivery> {

        private final long deliveryTime;
        private final long seq;
        private final CanFrame frame;

        private PendingDelivery(long deliveryTime, long seq, CanFrame frame) {
            this.deliveryTime = deliveryTime;
            this.seq = seq;
            this.frame = frame;
        }

        @Override
        public int compareTo(PendingDelivery other) {
            int byTime = Long.compare(deliveryTime, other.deliveryTime);
            return byTime != 0 ? byTime : Long.compare(seq, other.seq);
        }
    }
}
