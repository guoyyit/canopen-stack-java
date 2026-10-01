package com.github.guoyyit.canopenstackjava.canopen.sdo;

import com.github.guoyyit.canopenstackjava.canopen.core.CanBus;
import com.github.guoyyit.canopenstackjava.canopen.core.CanBusListener;
import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;

import java.io.ByteArrayOutputStream;

/**
 * SDO 客户端（主站侧）：同步发起读/写。
 * 不超过 4 字节走加速传输，超过 4 字节走分段传输；支持超时与中止，
 * 任何失败都会向服务器发送中止帧并释放本地状态，使双方回到一致的空闲态。
 */
public class SdoClient implements CanBusListener {

    private static final int CLIENT_NODE_ID = 0;
    private static final long DEFAULT_TIMEOUT_MILLIS = 3000L;

    private final CanBus bus;
    private final VirtualClock clock;
    private final boolean synchronousBus;
    private long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;

    private final Object monitor = new Object();
    private volatile byte[] pendingResponse;

    public SdoClient(CanBus bus, VirtualClock clock) {
        this.bus = bus;
        this.clock = clock;
        this.synchronousBus = Boolean.parseBoolean(
                System.getProperty("canopen.sdo.synchronousBus", "true"));
        bus.attach(CLIENT_NODE_ID, this);
    }

    /**
     * 显式指定总线分发模型。模拟总线下服务端响应在 {@code bus.send} 内同步泵出，
     * 客户端无需等待；接入真实异步总线时设为 false，使用监视器限时等待。
     */
    public SdoClient(CanBus bus, VirtualClock clock, boolean synchronousBus) {
        this.bus = bus;
        this.clock = clock;
        this.synchronousBus = synchronousBus;
        bus.attach(CLIENT_NODE_ID, this);
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void onFrame(CanFrame frame) {
        int cobId = frame.getId();
        // 仅接收 SDO 服务器->客户端段（0x580+N）；0x600+N 请求帧回环必须忽略。
        if (cobId < 0x580 || cobId > 0x5FF || frame.getDataLength() < 8) {
            return;
        }
        synchronized (monitor) {
            pendingResponse = frame.getData();
            monitor.notifyAll();
        }
    }

    public byte[] upload(int nodeId, int index, int subindex) {
        byte[] initiate = new byte[8];
        initiate[0] = 0x40;
        putIndex(initiate, index, subindex);
        byte[] response = request(nodeId, index, subindex, initiate);

        int command = response[0] & 0xFF;
        if (command == 0x43 || (command & 0xF0) == 0x40) {
            boolean expedited = (command & 0x02) != 0;
            if (expedited) {
                int n = (command >>> 2) & 0x03;
                int length = (command & 0x01) != 0 ? 4 - n : 4;
                byte[] result = new byte[length];
                System.arraycopy(response, 4, result, 0, length);
                return result;
            }
            int totalSize = u32(response, 4);
            return uploadSegments(nodeId, index, subindex, totalSize);
        }
        throw new SdoException(AbortCode.SDO_PROTOCOL_ERROR,
                "unexpected upload initiate response 0x" + Integer.toHexString(command));
    }

    public void download(int nodeId, int index, int subindex, byte[] data) {
        byte[] initiate = new byte[8];
        if (data.length <= 4) {
            int n = 4 - data.length;
            initiate[0] = (byte) (0x23 | (n << 2) | 0x02 | 0x01);
            putIndex(initiate, index, subindex);
            System.arraycopy(data, 0, initiate, 4, data.length);
            byte[] response = request(nodeId, index, subindex, initiate);
            expectAck(response, index, subindex, 0x60);
            return;
        }

        initiate[0] = 0x21;
        putIndex(initiate, index, subindex);
        writeU32(initiate, 4, data.length);
        byte[] response = request(nodeId, index, subindex, initiate);
        expectAck(response, index, subindex, 0x60);

        int offset = 0;
        int toggle = 0;
        while (offset < data.length) {
            int chunk = Math.min(7, data.length - offset);
            boolean last = offset + chunk == data.length;
            int unused = 7 - chunk;
            byte[] segment = new byte[8];
            segment[0] = (byte) (0x00 | (toggle << 4) | (unused << 1) | (last ? 0x01 : 0x00));
            System.arraycopy(data, offset, segment, 1, chunk);
            response = request(nodeId, index, subindex, segment);
            int expected = 0x20 | (toggle << 4);
            // 下载段应答不携带索引/子索引，仅校验命令字节与 toggle。
            if ((response[0] & 0xFF) != expected) {
                throw protocolError(response[0] & 0xFF);
            }
            toggle ^= 1;
            offset += chunk;
        }
    }

    public void abort(int nodeId, int index, int subindex, AbortCode code) {
        sendAbort(nodeId, index, subindex, code);
    }

    public void shutdown() {
        bus.detach(CLIENT_NODE_ID);
    }

    private byte[] uploadSegments(int nodeId, int index, int subindex, int totalSize) {
        ByteArrayOutputStream accumulator = new ByteArrayOutputStream(Math.max(0, totalSize));
        int toggle = 0;
        while (true) {
            byte[] requestFrame = new byte[8];
            requestFrame[0] = 0x60;
            byte[] response = request(nodeId, index, subindex, requestFrame);
            int command = response[0] & 0xFF;
            if ((command & 0xE0) != 0x00) {
                throw protocolError(command);
            }
            int responseToggle = (command >>> 4) & 0x01;
            if (responseToggle != toggle) {
                throw new SdoException(AbortCode.TOGGLE_BIT_INVALID,
                        "bad toggle in upload segment");
            }
            int unused = (command >>> 1) & 0x07;
            boolean last = (command & 0x01) != 0;
            int chunk = 7 - unused;
            accumulator.write(response, 1, chunk);
            if (last) {
                return accumulator.toByteArray();
            }
            toggle ^= 1;
        }
    }

    private byte[] request(int nodeId, int index, int subindex, byte[] payload) {
        long start = clock.nowMillis();
        long deadline = start + timeoutMillis;
        synchronized (monitor) {
            pendingResponse = null;
        }
        // 发送必须在 monitor 之外：模拟总线同步泵出响应时 onFrame 会重新进入 monitor。
        bus.send(CLIENT_NODE_ID, new CanFrame(0x600 + nodeId, payload));
        synchronized (monitor) {
            while (pendingResponse == null) {
                if (synchronousBus) {
                    if (clock.nowMillis() - start > timeoutMillis) {
                        sendAbort(nodeId, index, subindex, AbortCode.SDO_TIMEOUT);
                        throw new SdoException(AbortCode.SDO_TIMEOUT,
                                "SDO timeout waiting for node " + nodeId);
                    }
                    // 模拟总线下未收到响应说明帧丢失/服务器挂起：直接超时。
                    break;
                } else {
                    long wallRemaining = deadline - clock.nowMillis();
                    if (wallRemaining <= 0L) {
                        sendAbort(nodeId, index, subindex, AbortCode.SDO_TIMEOUT);
                        throw new SdoException(AbortCode.SDO_TIMEOUT,
                                "SDO timeout waiting for node " + nodeId);
                    }
                    try {
                        monitor.wait(Math.min(wallRemaining, 5L));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        sendAbort(nodeId, index, subindex, AbortCode.GENERAL_ERROR);
                        throw new SdoException(AbortCode.GENERAL_ERROR, "SDO interrupted");
                    }
                }
            }
            if (pendingResponse == null) {
                sendAbort(nodeId, index, subindex, AbortCode.SDO_TIMEOUT);
                throw new SdoException(AbortCode.SDO_TIMEOUT,
                        "SDO timeout waiting for node " + nodeId);
            }
            byte[] response = pendingResponse;
            pendingResponse = null;
            checkAbort(response, nodeId);
            return response;
        }
    }

    private void checkAbort(byte[] response, int nodeId) {
        if ((response[0] & 0xFF) == 0x80) {
            int code = u32(response, 4);
            AbortCode abortCode = resolveCode(code);
            throw new SdoException(abortCode,
                    "node " + nodeId + " aborted SDO with 0x" + Integer.toHexString(code));
        }
    }

    private void expectAck(byte[] response, int index, int subindex, int expectedCommand) {
        if ((response[0] & 0xFF) != (expectedCommand & 0xFF)) {
            throw protocolError(response[0] & 0xFF);
        }
        int responseIndex = u16(response, 1);
        if (responseIndex != index || (response[3] & 0xFF) != subindex) {
            throw new SdoException(AbortCode.SDO_PROTOCOL_ERROR,
                    "SDO ack references different object");
        }
    }

    private SdoException protocolError(int command) {
        return new SdoException(AbortCode.SDO_PROTOCOL_ERROR,
                "unexpected SDO command 0x" + Integer.toHexString(command));
    }

    private void sendAbort(int nodeId, int index, int subindex, AbortCode code) {
        byte[] abortFrame = new byte[8];
        abortFrame[0] = (byte) 0x80;
        putIndex(abortFrame, index, subindex);
        writeU32(abortFrame, 4, code.getCode());
        bus.send(CLIENT_NODE_ID, new CanFrame(0x600 + nodeId, abortFrame));
    }

    private static AbortCode resolveCode(int code) {
        for (AbortCode abortCode : AbortCode.values()) {
            if (abortCode.getCode() == code) {
                return abortCode;
            }
        }
        return AbortCode.GENERAL_ERROR;
    }

    private static void putIndex(byte[] data, int index, int subindex) {
        data[1] = (byte) (index & 0xFF);
        data[2] = (byte) ((index >> 8) & 0xFF);
        data[3] = (byte) subindex;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void writeU32(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
