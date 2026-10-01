package com.github.guoyyit.canopenstackjava.sdo;

import com.github.guoyyit.canopenstackjava.can.CanBus;
import com.github.guoyyit.canopenstackjava.can.CanFrame;
import com.github.guoyyit.canopenstackjava.can.FrameListener;
import com.github.guoyyit.canopenstackjava.od.AbortCode;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SDO 客户端。每次 upload/download 阻塞等待服务端应答，
 * 支持加速与分段传输、协议超时、重复帧忽略以及对端 abort，
 * 失败后本地会话立即复位，可再次发起新传输。
 */
public final class SdoClient implements FrameListener {

    private static final long DEFAULT_TIMEOUT_MILLIS = 1000L;
    private static final int SEGMENT_PAYLOAD = 7;

    private final int clientNodeId;
    private final CanBus bus;
    private final ScheduledExecutorService scheduler;
    private final long timeoutMillis;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition reply = lock.newCondition();
    private final Map<Integer, PendingTransfer> transfers = new HashMap<>();

    private volatile boolean running;

    public SdoClient(int clientNodeId, CanBus bus, ScheduledExecutorService scheduler, long timeoutMillis) {
        this.clientNodeId = clientNodeId & 0x7F;
        this.bus = bus;
        this.scheduler = scheduler;
        this.timeoutMillis = timeoutMillis <= 0 ? DEFAULT_TIMEOUT_MILLIS : timeoutMillis;
    }

    public void start() {
        bus.addListener(this);
        running = true;
    }

    public void stop() {
        running = false;
        bus.removeListener(this);
        reset();
    }

    public byte[] upload(int serverNodeId, int index, int subIndex)
            throws SdoException, InterruptedException {
        PendingTransfer transfer = register(serverNodeId);
        try {
            byte[] request = new byte[8];
            request[0] = 0x40;
            request[1] = (byte) index;
            request[2] = (byte) (index >>> 8);
            request[3] = (byte) subIndex;
            armWatchdog(transfer);
            sendTo(serverNodeId, request);

            awaitInitial(transfer);
            if (transfer.expedited) {
                return Arrays.copyOf(transfer.expeditedData, transfer.expeditedLength);
            }

            ByteArrayOutputStream accumulated = new ByteArrayOutputStream();
            int toggle = 0;
            while (true) {
                byte[] seg = new byte[8];
                seg[0] = (byte) (0x60 | (toggle << 4));
                armWatchdog(transfer);
                sendTo(serverNodeId, seg);
                awaitSegment(transfer);
                if (transfer.segmentToggle != toggle) {
                    // 重复段：不重复累积，重发请求继续
                    continue;
                }
                accumulated.write(transfer.segmentBuffer, 0, transfer.segmentLength);
                boolean last = transfer.segmentLast;
                toggle ^= 1;
                if (last) {
                    break;
                }
            }
            return accumulated.toByteArray();
        } finally {
            unregister(transfer);
        }
    }

    public void download(int serverNodeId, int index, int subIndex, byte[] data)
            throws SdoException, InterruptedException {
        PendingTransfer transfer = register(serverNodeId);
        try {
            byte[] init = new byte[8];
            if (data.length <= 4) {
                init[0] = (byte) (0x20 | 0x01 | 0x02 | ((4 - data.length) << 2));
                init[1] = (byte) index;
                init[2] = (byte) (index >>> 8);
                init[3] = (byte) subIndex;
                System.arraycopy(data, 0, init, 4, data.length);
                armWatchdog(transfer);
                sendTo(serverNodeId, init);
                awaitAck(transfer);
                return;
            }
            init[0] = 0x21;
            init[1] = (byte) index;
            init[2] = (byte) (index >>> 8);
            init[3] = (byte) subIndex;
            init[4] = (byte) data.length;
            init[5] = (byte) (data.length >>> 8);
            init[6] = (byte) (data.length >>> 16);
            init[7] = (byte) (data.length >>> 24);
            armWatchdog(transfer);
            sendTo(serverNodeId, init);
            awaitAck(transfer);

            int offset = 0;
            int toggle = 0;
            while (offset < data.length) {
                int chunkLen = Math.min(SEGMENT_PAYLOAD, data.length - offset);
                int n = SEGMENT_PAYLOAD - chunkLen;
                boolean last = offset + chunkLen >= data.length;
                byte[] seg = new byte[8];
                seg[0] = (byte) (0x60 | (toggle << 4) | (n << 1) | (last ? 0x01 : 0x00));
                System.arraycopy(data, offset, seg, 1, chunkLen);
                armWatchdog(transfer);
                sendTo(serverNodeId, seg);
                awaitAck(transfer);
                offset += chunkLen;
                toggle ^= 1;
            }
        } finally {
            unregister(transfer);
        }
    }

    /** 复位（如节点重启）：以 RESOURCE_UNAVAILABLE 唤醒所有等待者，会话不悬挂。 */
    public void reset() {
        lock.lock();
        try {
            for (PendingTransfer transfer : transfers.values()) {
                transfer.aborted = true;
                transfer.abortCode = AbortCode.RESOURCE_UNAVAILABLE.getCode();
                cancelWatchdog(transfer);
                transfer.done = true;
                reply.signalAll();
            }
            transfers.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onFrame(CanFrame frame) {
        if (!running) {
            return;
        }
        int serverNodeId = frame.getCobId() - 0x580;
        if (serverNodeId < 1 || serverNodeId > 127) {
            return;
        }
        byte[] d = frame.dataBytes();
        if (d.length < 8) {
            return;
        }
        lock.lock();
        try {
            PendingTransfer transfer = transfers.get(serverNodeId);
            if (transfer == null) {
                return;
            }
            cancelWatchdog(transfer);
            if ((d[0] & 0xE0) == 0x80) {
                transfer.abortCode = (int) u32(d, 4);
                transfer.aborted = true;
            } else {
                decode(transfer, d);
            }
            transfer.done = true;
            reply.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void decode(PendingTransfer transfer, byte[] d) {
        int scs = d[0] & 0xE0;
        if (scs == 0x40) {
            if ((d[0] & 0x02) != 0) {
                int n = (d[0] >>> 2) & 0x03;
                transfer.expedited = true;
                transfer.expeditedLength = 4 - n;
                transfer.expeditedData = Arrays.copyOfRange(d, 4, 8);
            } else {
                transfer.expedited = false;
                transfer.segmentLength = 0;
            }
        } else if (scs == 0x60) {
        } else if (scs == 0x00) {
            int toggle = (d[0] >>> 4) & 0x01;
            int n = (d[0] >>> 1) & 0x07;
            int len = SEGMENT_PAYLOAD - n;
            transfer.segmentToggle = toggle;
            transfer.segmentLength = len;
            transfer.segmentLast = (d[0] & 0x01) != 0;
            transfer.segmentBuffer = Arrays.copyOfRange(d, 1, 1 + len);
        }
    }

    private PendingTransfer register(int serverNodeId) throws SdoException {
        lock.lock();
        try {
            PendingTransfer existing = transfers.get(serverNodeId);
            if (existing != null && !existing.done) {
                throw new SdoException(AbortCode.RESOURCE_UNAVAILABLE.getCode(),
                        "SDO channel to node " + serverNodeId + " is busy");
            }
            PendingTransfer transfer = new PendingTransfer(serverNodeId);
            transfers.put(serverNodeId, transfer);
            return transfer;
        } finally {
            lock.unlock();
        }
    }

    private void unregister(PendingTransfer transfer) {
        lock.lock();
        try {
            cancelWatchdog(transfer);
            transfers.remove(transfer.serverNodeId, transfer);
        } finally {
            lock.unlock();
        }
    }

    private void awaitInitial(PendingTransfer transfer) throws InterruptedException {
        await(transfer, false);
    }

    private void awaitSegment(PendingTransfer transfer) throws InterruptedException {
        await(transfer, true);
    }

    private void awaitAck(PendingTransfer transfer) throws InterruptedException {
        await(transfer, false);
    }

    private void await(PendingTransfer transfer, boolean segment) throws InterruptedException {
        lock.lock();
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (!transfer.done) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    cancelWatchdog(transfer);
                    throw new SdoException(AbortCode.TIMEOUT.getCode(),
                            "SDO timeout waiting for node " + transfer.serverNodeId);
                }
                reply.awaitNanos(remainingNanos);
            }
            cancelWatchdog(transfer);
            if (transfer.aborted) {
                int code = transfer.abortCode;
                throw new SdoException(code,
                        "SDO aborted by node " + transfer.serverNodeId + " with code 0x"
                                + String.format("%08X", code));
            }
            transfer.done = false;
        } finally {
            lock.unlock();
        }
    }

    private void armWatchdog(PendingTransfer transfer) {
        transfer.watchdog = scheduler.schedule(() -> {
            lock.lock();
            try {
                if (!transfer.done) {
                    transfer.aborted = true;
                    transfer.abortCode = AbortCode.TIMEOUT.getCode();
                    transfer.done = true;
                    reply.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelWatchdog(PendingTransfer transfer) {
        ScheduledFuture<?> future = transfer.watchdog;
        if (future != null) {
            future.cancel(false);
            transfer.watchdog = null;
        }
    }

    private void sendTo(int serverNodeId, byte[] data) {
        bus.send(new CanFrame(0x600 + (serverNodeId & 0x7F), data, 0));
    }

    private static long u32(byte[] d, int off) {
        return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8)
                | ((d[off + 2] & 0xFFL) << 16) | ((d[off + 3] & 0xFFL) << 24);
    }

    private static final class PendingTransfer {
        private final int serverNodeId;
        private volatile boolean done;
        private volatile boolean aborted;
        private volatile int abortCode;

        private volatile boolean expedited;
        private volatile byte[] expeditedData = new byte[0];
        private volatile int expeditedLength;

        private volatile byte[] segmentBuffer = new byte[0];
        private volatile int segmentLength;
        private volatile boolean segmentLast;
        private volatile int segmentToggle;
        private volatile ScheduledFuture<?> watchdog;

        private PendingTransfer(int serverNodeId) {
            this.serverNodeId = serverNodeId;
        }
    }
}
