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
 *   <li>分段传输严格校验 toggle 位，重复段被幂等忽略并原样重发上一应答；</li>
 *   <li>任何非法请求以标准中止码回答并立即释放会话；</li>
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
            // 客户端主动中止：释放匹配会话。
            if (session != null && session.index == u16(data, 1) && session.subindex == data[3]) {
                session = null;
            }
            return;
        }

        int index = u16(data, 1);
        int subindex = data[3] & 0xFF;
        try {
            if ((command & 0xE0) == 0x00 && session != null && session.downloading) {
                handleDownloadSegment(command, data);
            } else if (command == 0x20 || command == 0x21
                    || command == 0x22 || command == 0x23
                    || ((command & 0xE0) == 0x20)) {
                handleInitiateDownload(index, subindex, data);
            } else if (command == 0x40) {
                handleInitiateUpload(index, subindex);
            } else if (command == 0x60 && session != null
                    && session.uploading) {
                handleUploadSegment(command);
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
        if (session == null) {
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

    public int activeSessionCount() {
        return session == null ? 0 : 1;
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
            node.getObjectDictionary().write(index, subindex, value);
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
            if (toggle == (current.expectedToggle ^ 1) && current.lastAck != null) {
                // 重复段：丢弃数据，原样重发上一应答（幂等）。
                send(current.lastAck);
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
        current.lastAck = ack.clone();
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
            node.getObjectDictionary().write(index, subindex, value);
            session = null;
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
        // 上传段请求固定为 0x60（不含 toggle）。客户端收到应答后才发下一个 0x60，
        // 因此收到 0x60 即表示上一应答已确认；首段/重复首段由 expectedToggle==0 区分。
        if (current.awaitingAck) {
            // 上一段已确认，推进 toggle；awaitingAck 在发送后重新置位。
            current.expectedToggle ^= 1;
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
        current.lastSegmentResponse = response.clone();
        current.awaitingAck = true;
        send(response);
        if (last) {
            session = null;
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
        private int expectedToggle;
        private ByteArrayOutputStream accumulator;
        private int totalSize = -1;
        private byte[] lastAck;

        private byte[] pendingData;
        private int pendingOffset;
        private byte[] lastSegmentResponse;
        private boolean awaitingAck;

        private Session(int index, int subindex, long nowMillis) {
            this.index = index;
            this.subindex = subindex;
            this.lastActivityMillis = nowMillis;
        }
    }
}
