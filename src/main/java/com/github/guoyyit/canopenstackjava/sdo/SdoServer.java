package com.github.guoyyit.canopenstackjava.sdo;

import com.github.guoyyit.canopenstackjava.can.CanBus;
import com.github.guoyyit.canopenstackjava.can.CanFrame;
import com.github.guoyyit.canopenstackjava.can.FrameListener;
import com.github.guoyyit.canopenstackjava.nmt.NmtStateMachine;
import com.github.guoyyit.canopenstackjava.od.AbortCode;
import com.github.guoyyit.canopenstackjava.od.OdAccessException;
import com.github.guoyyit.canopenstackjava.od.ObjectDictionary;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SDO 服务端（CiA 301 加速传输 + 分段传输）。
 *
 * <p>处理要点：
 * <ul>
 *   <li>NMT 门禁：仅 PRE_OPERATIONAL / OPERATIONAL 响应 SDO；</li>
 *   <li>分段传输严格校验 toggle，重复帧（同一 toggle）重发上一应答而不重复写数据；</li>
 *   <li>看门狗超时：本地中止并尽力发送 abort，双方状态一致且可恢复；</li>
 *   <li>任意阶段 OD 拒绝均映射为标准 abort code。</li>
 * </ul>
 */
public final class SdoServer implements FrameListener {

    static final long DEFAULT_TIMEOUT_MILLIS = 1000L;
    private static final int EXPEDITED_LIMIT = 4;
    private static final int SEGMENT_PAYLOAD = 7;

    private final int nodeId;
    private final int rxId;
    private final int txId;
    private final CanBus bus;
    private final ObjectDictionary od;
    private final NmtStateMachine nmt;
    private final ScheduledExecutorService scheduler;
    private final long timeoutMillis;

    private final Object lock = new Object();
    private final Map<Integer, SessionContext> sessions = new HashMap<>();
    private volatile boolean running;
    private volatile boolean selfRegistered = true;

    public SdoServer(int nodeId, CanBus bus, ObjectDictionary od, NmtStateMachine nmt,
                     ScheduledExecutorService scheduler, long timeoutMillis) {
        this.nodeId = nodeId & 0x7F;
        this.rxId = 0x600 + this.nodeId;
        this.txId = 0x580 + this.nodeId;
        this.bus = bus;
        this.od = od;
        this.nmt = nmt;
        this.scheduler = scheduler;
        this.timeoutMillis = timeoutMillis <= 0 ? DEFAULT_TIMEOUT_MILLIS : timeoutMillis;
    }

    /**
     * 受管模式：由节点执行器统一串行分发 onFrame，服务端不再自行注册为总线监听器，
     * 保证状态迁移与帧处理在同一全序下发生。
     */
    public SdoServer withManagedDispatch() {
        this.selfRegistered = false;
        return this;
    }

    public void start() {
        if (selfRegistered) {
            bus.addListener(this);
        }
        running = true;
    }

    public void stop() {
        running = false;
        if (selfRegistered) {
            bus.removeListener(this);
        }
        reset();
    }

    /** 通信复位 / 节点复位：终止全部挂起会话，等待中的对端以 abort 感知后恢复。 */
    public void reset() {
        synchronized (lock) {
            for (Map.Entry<Integer, SessionContext> entry : sessions.entrySet()) {
                SessionContext context = entry.getValue();
                cancelWatchdog(context);
                abortInternal(context, AbortCode.RESOURCE_UNAVAILABLE.getCode(),
                        context.session.getIndex(), context.session.getSubIndex(), true);
            }
            sessions.clear();
        }
    }

    @Override
    public void onFrame(CanFrame frame) {
        if (!running || frame.getCobId() != rxId) {
            return;
        }
        if (frame.getCobId() == txId) {
            return;
        }
        if (!nmt.getState().allowsSdo()) {
            return;
        }
        int clientNodeId = frame.getCobId() - 0x600;
        byte[] d = frame.dataBytes();
        if (d.length < 4) {
            return;
        }
        synchronized (lock) {
            SessionContext context = sessions.computeIfAbsent(clientNodeId, k -> new SessionContext());
            cancelWatchdog(context);
            try {
                dispatch(context, d);
            } catch (AbortSignal signal) {
                abortInternal(context, signal.abortCode, u16(d, 1), d[3] & 0xFF, true);
            } catch (OdAccessException e) {
                abortInternal(context, e.getAbortCode(), u16(d, 1), d[3] & 0xFF, true);
            } catch (RuntimeException e) {
                abortInternal(context, AbortCode.GENERAL_ERROR.getCode(),
                        u16(d, 1), d[3] & 0xFF, true);
            }
            if (context.session.isActive()) {
                armWatchdog(context);
            } else {
                sessions.remove(clientNodeId);
            }
        }
    }

    private void dispatch(SessionContext context, byte[] d) {
        int spec = d[0] & 0xE0;
        if (spec == 0x80) {
            context.session.reset();
            return;
        }
        if (spec == 0x20) {
            handleInitiateDownload(context, d);
            return;
        }
        if (spec == 0x40) {
            handleInitiateUpload(context, d);
            return;
        }
        if (spec == 0x60) {
            int state = context.session.getState();
            if (state == SdoSession.UPLOAD_INIT || state == SdoSession.UPLOAD_SEGMENT) {
                handleUploadSegmentRequest(context, d);
            } else {
                handleDownloadSegment(context, d);
            }
            return;
        }
        throw new AbortSignal(AbortCode.INVALID_COMMAND_SPECIFIER.getCode());
    }

    private void handleInitiateDownload(SessionContext context, byte[] d) {
        int index = u16(d, 1);
        int sub = d[3] & 0xFF;
        boolean expedited = (d[0] & 0x02) != 0;
        boolean sizeIndicated = (d[0] & 0x01) != 0;
        if (expedited) {
            com.github.guoyyit.canopenstackjava.od.OdEntry entry = od.getEntry(index, sub);
            if (entry == null) {
                throw new AbortSignal(AbortCode.OBJECT_DOES_NOT_EXIST.getCode());
            }
            int expected = entry.expectedSize();
            int length;
            if (sizeIndicated) {
                int n = (d[0] >>> 2) & 0x03;
                length = 4 - n;
            } else if (expected > 0 && expected <= EXPEDITED_LIMIT) {
                length = expected;
            } else {
                length = 4;
            }
            byte[] value = Arrays.copyOfRange(d, 4, 4 + length);
            od.write(index, sub, value);
            context.session.reset();
            send(buildSimpleAck(0x60, index, sub));
        } else {
            long size = sizeIndicated ? u32(d, 4) : -1L;
            context.session.beginDownload(index, sub, size);
            send(buildSimpleAck(0x60, index, sub));
        }
    }

    private void handleDownloadSegment(SessionContext context, byte[] d) {
        SdoSession session = context.session;
        if (session.getState() != SdoSession.DOWNLOAD_INIT
                && session.getState() != SdoSession.DOWNLOAD_SEGMENT) {
            throw new AbortSignal(AbortCode.INVALID_COMMAND_SPECIFIER.getCode());
        }
        int toggle = (d[0] >>> 4) & 0x01;
        if (session.getState() == SdoSession.DOWNLOAD_INIT) {
            if (toggle != 0) {
                throw new AbortSignal(AbortCode.INVALID_SEQUENCE.getCode());
            }
            session.beginDownloadSegments();
        } else {
            if (toggle != session.getToggle()) {
                // 重复段（旧 toggle）：重发上一应答，绝不重复追加数据
                if (context.lastAck != null) {
                    send(context.lastAck);
                    return;
                }
                throw new AbortSignal(AbortCode.INVALID_SEQUENCE.getCode());
            }
        }
        int n = (d[0] >>> 1) & 0x07;
        int length = SEGMENT_PAYLOAD - n;
        byte[] chunk = Arrays.copyOfRange(d, 1, 1 + Math.max(length, 0));
        session.appendDownload(chunk);
        byte[] ack = new byte[]{(byte) (0x20 | (session.getToggle() << 4)), 0, 0, 0, 0, 0, 0, 0};
        context.lastAck = ack;
        send(ack);
        session.flipToggle();
        boolean last = (d[0] & 0x01) != 0;
        if (last) {
            od.write(session.getIndex(), session.getSubIndex(), session.downloadBytes());
            session.reset();
        }
    }

    private void handleInitiateUpload(SessionContext context, byte[] d) {
        int index = u16(d, 1);
        int sub = d[3] & 0xFF;
        byte[] data = od.read(index, sub);
        if (data.length <= EXPEDITED_LIMIT) {
            byte[] response = new byte[8];
            int n = 4 - data.length;
            response[0] = (byte) (0x40 | 0x02 | 0x01 | (n << 2));
            response[1] = d[1];
            response[2] = d[2];
            response[3] = d[3];
            System.arraycopy(data, 0, response, 4, data.length);
            context.session.reset();
            send(response);
        } else {
            context.session.beginUpload(index, sub, data);
            byte[] response = new byte[8];
            response[0] = 0x41;
            response[1] = d[1];
            response[2] = d[2];
            response[3] = d[3];
            response[4] = (byte) data.length;
            response[5] = (byte) (data.length >>> 8);
            response[6] = (byte) (data.length >>> 16);
            response[7] = (byte) (data.length >>> 24);
            send(response);
        }
    }

    private void handleUploadSegmentRequest(SessionContext context, byte[] d) {
        SdoSession session = context.session;
        if (session.getState() != SdoSession.UPLOAD_INIT
                && session.getState() != SdoSession.UPLOAD_SEGMENT) {
            throw new AbortSignal(AbortCode.INVALID_COMMAND_SPECIFIER.getCode());
        }
        int requestedToggle = (d[0] >>> 4) & 0x01;
        if (session.getState() == SdoSession.UPLOAD_SEGMENT) {
            if (requestedToggle != session.getToggle()) {
                // 重复请求：重发上一段，不移动偏移
                if (context.lastAck != null) {
                    send(context.lastAck);
                    return;
                }
                throw new AbortSignal(AbortCode.INVALID_SEQUENCE.getCode());
            }
        }
        if (session.getState() == SdoSession.UPLOAD_INIT) {
            session.beginUploadSegments();
        }
        byte[] all = session.uploadData();
        int offset = session.uploadOffset();
        int remaining = all.length - offset;
        int chunkLen = Math.min(SEGMENT_PAYLOAD, remaining);
        byte[] response = new byte[8];
        int n = SEGMENT_PAYLOAD - chunkLen;
        boolean last = offset + chunkLen >= all.length;
        response[0] = (byte) ((session.getToggle() << 4) | (n << 1) | (last ? 0x01 : 0x00));
        System.arraycopy(all, offset, response, 1, chunkLen);
        context.lastAck = response;
        send(response);
        session.advanceUpload(chunkLen);
        session.flipToggle();
        if (last) {
            session.reset();
        }
    }

    private void abortInternal(SessionContext context, int code, int index, int sub, boolean notifyPeer) {
        SdoSession session = context.session;
        if (notifyPeer) {
            byte[] frame = new byte[8];
            frame[0] = (byte) 0x80;
            int idx = session.isActive() ? session.getIndex() : index;
            int sb = session.isActive() ? session.getSubIndex() : sub;
            frame[1] = (byte) idx;
            frame[2] = (byte) (idx >>> 8);
            frame[3] = (byte) sb;
            frame[4] = (byte) code;
            frame[5] = (byte) (code >>> 8);
            frame[6] = (byte) (code >>> 16);
            frame[7] = (byte) (code >>> 24);
            send(frame);
        }
        cancelWatchdog(context);
        session.reset();
        context.lastAck = null;
    }

    private void armWatchdog(SessionContext context) {
        context.watchdog = scheduler.schedule(() -> {
            synchronized (lock) {
                if (context.session.isActive()) {
                    abortInternal(context, AbortCode.TIMEOUT.getCode(),
                            context.session.getIndex(), context.session.getSubIndex(), true);
                }
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelWatchdog(SessionContext context) {
        ScheduledFuture<?> future = context.watchdog;
        if (future != null) {
            future.cancel(false);
            context.watchdog = null;
        }
    }

    private void send(byte[] data) {
        bus.send(new CanFrame(txId, data, 0));
    }

    private static byte[] buildSimpleAck(int scs, int index, int sub) {
        return new byte[]{(byte) scs, (byte) index, (byte) (index >>> 8), (byte) sub, 0, 0, 0, 0};
    }

    private static int u16(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] d, int off) {
        return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8)
                | ((d[off + 2] & 0xFFL) << 16) | ((d[off + 3] & 0xFFL) << 24);
    }

    private static final class SessionContext {
        private final SdoSession session = new SdoSession();
        private volatile byte[] lastAck;
        private volatile ScheduledFuture<?> watchdog;
    }

    private static final class AbortSignal extends RuntimeException {
        private final int abortCode;

        private AbortSignal(int abortCode) {
            super(null, null, true, false);
            this.abortCode = abortCode;
        }
    }
}
