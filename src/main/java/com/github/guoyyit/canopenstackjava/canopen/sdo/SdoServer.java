package com.github.guoyyit.canopenstackjava.canopen.sdo;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;
import com.github.guoyyit.canopenstackjava.canopen.od.OdEntry;
import com.github.guoyyit.canopenstackjava.canopen.od.OdException;

import java.io.ByteArrayOutputStream;

/**
 * SDO 服务器（从站侧）。
 *
 * <ul>
 *   <li>不超过 4 字节的对象走加速传输，超过 4 字节走分段传输；</li>
 *   <li>分段传输按 CiA 301 校验 toggle 位：请求侧与应答侧对同一段的 toggle 判断一致，
 *       首段 toggle 为 0，每确认一段双方同步翻转；</li>
 *   <li>重复段（与上一已处理段 toggle 相同）被识别为合法重复：丢弃重复数据、
 *       原样重发上一次应答，既不推进会话也不重复累积；</li>
 *   <li>传输正常完成后保留末次应答作为“墓碑”：末段请求的合法重复仍按相同结果回答，
 *       不计为活动会话；任何新 initiate、中止或复位都会清除墓碑；</li>
 *   <li>真正乱序（toggle 既非期望亦非上一段）以 {@link AbortCode#TOGGLE_BIT_INVALID} 拒绝，
 *       无会话的段帧以 {@link AbortCode#SDO_COMMAND_SPECIFIER_INVALID} 拒绝，
 *       拒绝后双方均不残留会话；</li>
 *   <li>onTick 检测空闲超时，超时发送中止帧并释放会话，双方均可恢复。</li>
 * </ul>
 */
public class SdoServer {

    private static final long DEFAULT_TIMEOUT_MILLIS = 3000L;
    private static final int CLIENT_COB_BASE = 0x600;
    private static final int SERVER_COB_BASE = 0x580;

    private static final int ABORT_CS = 0x80;

    private final CanOpenNode node;

    private Session session;
    private long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;

    public SdoServer(CanOpenNode node) {
        this.node = node;
        long configured = node.getConfig().getSdoTimeoutMillis();
        if (configured > 0L) {
            this.timeoutMillis = configured;
        }
    }

    public void start() {
        abortAllSessions();
    }

    public void shutdown() {
        abortAllSessions();
    }

    public void onFrame(CanFrame frame) {
        if (frame.getId() != CLIENT_COB_BASE + node.getNodeId() || frame.getDataLength() < 8) {
            return;
        }
        byte[] data = frame.getData();
        int command = data[0] & 0xFF;
        if (command == ABORT_CS) {
            // 客户端主动中止：释放匹配会话（含已完成墓碑）。
            if (session != null && session.index == u16(data, 1) && session.subindex == data[3]) {
                session = null;
            }
            return;
        }

        int index = u16(data, 1);
        int subindex = data[3] & 0xFF;
        try {
            if ((command & 0xE0) == 0x00) {
                // 下载段请求（ccs=0）。
                if (isActiveDownload()) {
                    handleDownloadSegment(command, data);
                } else if (isCompletedDuplicateOf(command, true)) {
                    send(session.lastResponse);
                } else {
                    abort(index, subindex, AbortCode.SDO_COMMAND_SPECIFIER_INVALID);
                }
            } else if ((command & 0xE0) == 0x20) {
                handleInitiateDownload(index, subindex, data);
            } else if (command == 0x40) {
                handleInitiateUpload(index, subindex);
            } else if ((command & 0xE0) == 0x60) {
                // 上传段请求（ccs=3，toggle 在 bit4）。
                if (isActiveUpload()) {
                    handleUploadSegment(command);
                } else if (isCompletedDuplicateOf(command, false)) {
                    send(session.lastResponse);
                } else {
                    abort(index, subindex, AbortCode.SDO_COMMAND_SPECIFIER_INVALID);
                }
            } else {
                abort(index, subindex, AbortCode.SDO_COMMAND_SPECIFIER_INVALID);
            }
        } catch (OdException ex) {
            abort(index, subindex, ex.getAbortCode());
        } catch (RuntimeException ex) {
            abort(index, subindex, AbortCode.GENERAL_ERROR);
        }
    }

    public void onTick(long nowMillis) {
        if (session == null || session.completed) {
            return;
        }
        if (nowMillis - session.lastActivityMillis > timeoutMillis) {
            int index = session.index;
            int subindex = session.subindex;
            session = null;
            // 超时通知对端；对端若已自行释放也会安全忽略该中止帧。
            sendAbort(index, subindex, AbortCode.SDO_TIMEOUT);
        }
    }

    public void abortAllSessions() {
        session = null;
    }

    /** 活动会话数：正常完成后的墓碑不计入。 */
    public int activeSessionCount() {
        return session != null && !session.completed ? 1 : 0;
    }

    private boolean isActiveDownload() {
        return session != null && !session.completed && session.downloading;
    }

    private boolean isActiveUpload() {
        return session != null && !session.completed && session.uploading;
    }

    /** 已完成会话的末段请求合法重复：方向一致且 toggle 与末次处理的请求一致。 */
    private boolean isCompletedDuplicateOf(int command, boolean download) {
        return session != null && session.completed
                && (download ? session.downloading : session.uploading)
                && session.lastResponse != null
                && ((command >>> 4) & 0x01) == session.lastRequestToggle;
    }

    // ---- 下载（客户端写服务器）----

    private void handleInitiateDownload(int index, int subindex, byte[] data) {
        OdEntry entry = node.getObjectDictionary().entry(index, subindex);
        if (!entry.getAccess().isWritable()) {
            throw new OdException(AbortCode.WRITE_ON_READ_ONLY);
        }
        boolean expedited = (data[0] & 0x02) != 0;
        boolean sizeIndicated = (data[0] & 0x01) != 0;

        if (expedited) {
            if (!sizeIndicated) {
                throw new OdException(AbortCode.SDO_PROTOCOL_ERROR);
            }
            int n = (data[0] >>> 2) & 0x03;
            int length = 4 - n;
            byte[] payload = new byte[length];
            System.arraycopy(data, 4, payload, 0, length);
            Object value = SdoCodec.decodeValue(entry, payload);
            node.writeExternal(index, subindex, value);
            send(new byte[]{0x60, (byte) (index & 0xFF), (byte) ((index >> 8) & 0xFF),
                    (byte) subindex, 0, 0, 0, 0});
            return;
        }

        int totalSize = sizeIndicated ? ((data[4] & 0xFF)
                | ((data[5] & 0xFF) << 8)
                | ((data[6] & 0xFF) << 16)
                | ((data[7] & 0xFF) << 24)) : -1;
        Session newSession = new Session(index, subindex, node.getClock().nowMillis());
        newSession.downloading = true;
        newSession.expectedToggle = 0;
        newSession.accumulator = new ByteArrayOutputStream(Math.max(0, totalSize));
        newSession.totalSize = totalSize;
        this.session = newSession;
        send(new byte[]{0x60, (byte) (index & 0xFF), (byte) ((index >> 8) & 0xFF),
                (byte) subindex, 0, 0, 0, 0});
    }

    private void handleDownloadSegment(int command, byte[] data) {
        Session current = session;
        int index = current.index;
        int subindex = current.subindex;
        int toggle = (command >>> 4) & 0x01;
        if (toggle != current.expectedToggle) {
            if (toggle == current.lastRequestToggle && current.lastResponse != null) {
                // 重复段：丢弃数据，原样重发上一应答（幂等，不重复累积）。
                send(current.lastResponse);
                return;
            }
            throw new OdException(AbortCode.TOGGLE_BIT_INVALID);
        }
        boolean lastSegment = (command & 0x01) != 0;
        int unused = (command >>> 1) & 0x07;
        int segmentBytes = 7 - unused;
        current.accumulator.write(data, 1, segmentBytes);
        // 下载段应答：010 t 0000（toggle 在 bit4），高 3 位固定 010。
        byte[] ack = new byte[]{
                (byte) (0x20 | ((current.expectedToggle & 0x01) << 4)),
                0, 0, 0, 0, 0, 0, 0};
        current.lastResponse = ack.clone();
        current.lastRequestToggle = toggle;
        current.expectedToggle ^= 1;
        current.lastActivityMillis = node.getClock().nowMillis();
        send(ack);

        if (lastSegment) {
            byte[] complete = current.accumulator.toByteArray();
            if (current.totalSize >= 0 && complete.length != current.totalSize) {
                abort(index, subindex, AbortCode.SDO_PROTOCOL_ERROR);
                return;
            }
            OdEntry entry = node.getObjectDictionary().entry(index, subindex);
            Object value = SdoCodec.decodeValue(entry, complete);
            node.writeExternal(index, subindex, value);
            // 正常完成：保留末次应答作为墓碑，吸收末段请求的合法重复。
            current.completed = true;
        }
    }

    // ---- 上传（客户端读服务器）----

    private void handleInitiateUpload(int index, int subindex) {
        OdEntry entry = node.getObjectDictionary().entry(index, subindex);
        if (!entry.getAccess().isReadable()) {
            throw new OdException(AbortCode.READ_ON_WRITE_ONLY);
        }
        byte[] payload = SdoCodec.encodeEntry(entry);
        if (payload.length <= 4) {
            int n = 4 - payload.length;
            byte[] response = new byte[8];
            response[0] = (byte) (0x43 | (n << 2) | 0x02 | 0x01);
            response[1] = (byte) (index & 0xFF);
            response[2] = (byte) ((index >> 8) & 0xFF);
            response[3] = (byte) subindex;
            System.arraycopy(payload, 0, response, 4, payload.length);
            send(response);
            return;
        }

        Session newSession = new Session(index, subindex, node.getClock().nowMillis());
        newSession.uploading = true;
        newSession.expectedToggle = 0;
        newSession.pendingData = payload;
        newSession.pendingOffset = 0;
        this.session = newSession;

        byte[] response = new byte[8];
        response[0] = 0x41;
        response[1] = (byte) (index & 0xFF);
        response[2] = (byte) ((index >> 8) & 0xFF);
        response[3] = (byte) subindex;
        writeU32(response, 4, payload.length);
        send(response);
    }

    private void handleUploadSegment(int command) {
        Session current = session;
        // CiA 301：上传段请求为 0110 t 0000，服务器校验请求 toggle 与期望一致；
        // 与上一已处理请求 toggle 相同的为合法重复，原样重发上一段应答，不推进偏移。
        int toggle = (command >>> 4) & 0x01;
        if (toggle != current.expectedToggle) {
            if (toggle == current.lastRequestToggle && current.lastResponse != null) {
                send(current.lastResponse);
                return;
            }
            throw new OdException(AbortCode.TOGGLE_BIT_INVALID);
        }
        int remaining = current.pendingData.length - current.pendingOffset;
        int chunk = Math.min(7, remaining);
        boolean last = current.pendingOffset + chunk == current.pendingData.length;
        int unused = 7 - chunk;
        byte[] response = new byte[8];
        response[0] = (byte) ((current.expectedToggle << 4) | (unused << 1)
                | (last ? 0x01 : 0x00));
        System.arraycopy(current.pendingData, current.pendingOffset, response, 1, chunk);
        current.pendingOffset += chunk;
        current.lastActivityMillis = node.getClock().nowMillis();
        current.lastResponse = response.clone();
        current.lastRequestToggle = toggle;
        current.expectedToggle ^= 1;
        send(response);
        if (last) {
            // 正常完成：保留末段应答作为墓碑，吸收末次请求的合法重复。
            current.completed = true;
        }
    }

    private void abort(int index, int subindex, AbortCode code) {
        session = null;
        sendAbort(index, subindex, code);
    }

    private void sendAbort(int index, int subindex, AbortCode code) {
        byte[] response = new byte[8];
        response[0] = (byte) ABORT_CS;
        response[1] = (byte) (index & 0xFF);
        response[2] = (byte) ((index >> 8) & 0xFF);
        response[3] = (byte) subindex;
        writeU32(response, 4, code.getCode());
        send(response);
    }

    private void send(byte[] data) {
        node.getBus().send(node.getNodeId(),
                new CanFrame(SERVER_COB_BASE + node.getNodeId(), data));
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static void writeU32(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static final class Session {

        private final int index;
        private final int subindex;
        private long lastActivityMillis;

        private boolean downloading;
        private boolean uploading;
        /** 正常完成标记：会话转为墓碑，仅用于吸收末段请求的合法重复。 */
        private boolean completed;
        /** 下一个期望处理的请求 toggle。 */
        private int expectedToggle;
        /** 最近一次已处理请求的 toggle；无已处理请求时为 -1。 */
        private int lastRequestToggle = -1;
        /** 最近一次发送的段级应答，用于合法重复时的幂等重发。 */
        private byte[] lastResponse;

        private ByteArrayOutputStream accumulator;
        private int totalSize = -1;

        private byte[] pendingData;
        private int pendingOffset;

        private Session(int index, int subindex, long nowMillis) {
            this.index = index;
            this.subindex = subindex;
            this.lastActivityMillis = nowMillis;
        }
    }
}
