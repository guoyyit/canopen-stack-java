package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.CanBus;
import com.github.guoyyit.canopenstackjava.canopen.core.CanBusListener;
import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的裸 SDO 主站：逐帧发送请求并收集 0x580+N 应答，
 * 使测试可以在分段传输的任意两段之间插入 NMT 命令、故障切换等交错动作。
 */
final class RawSdoMaster implements CanBusListener {

    private final CanBus bus;
    private final List<CanFrame> responses = new ArrayList<>();

    private RawSdoMaster(CanBus bus) {
        this.bus = bus;
    }

    /** 以独立监听器 id 挂载（不得与节点 id、SdoClient(0)、FrameRecorder 冲突）。 */
    static RawSdoMaster attach(CanBus bus, int listenerId) {
        RawSdoMaster master = new RawSdoMaster(bus);
        bus.attach(listenerId, master);
        return master;
    }

    @Override
    public void onFrame(CanFrame frame) {
        int cobId = frame.getId();
        if (cobId >= 0x580 && cobId <= 0x5FF && frame.getDataLength() >= 8) {
            synchronized (responses) {
                responses.add(frame);
            }
        }
    }

    void send(int nodeId, byte[] payload) {
        bus.send(0, new CanFrame(0x600 + nodeId, payload));
    }

    /** 发起上传（0x40）。 */
    void initiateUpload(int nodeId, int index, int subindex) {
        byte[] frame = new byte[8];
        frame[0] = 0x40;
        putIndex(frame, index, subindex);
        send(nodeId, frame);
    }

    /** 上传段请求（0x60 | toggle<<4）。 */
    void uploadSegmentRequest(int nodeId, int toggle) {
        byte[] frame = new byte[8];
        frame[0] = (byte) (0x60 | ((toggle & 0x01) << 4));
        send(nodeId, frame);
    }

    /** 发起分段下载（0x21，携带总长度）。 */
    void initiateDownload(int nodeId, int index, int subindex, int totalSize) {
        byte[] frame = new byte[8];
        frame[0] = 0x21;
        putIndex(frame, index, subindex);
        frame[4] = (byte) (totalSize & 0xFF);
        frame[5] = (byte) ((totalSize >>> 8) & 0xFF);
        frame[6] = (byte) ((totalSize >>> 16) & 0xFF);
        frame[7] = (byte) ((totalSize >>> 24) & 0xFF);
        send(nodeId, frame);
    }

    /** 下载段（ccs=0，toggle、数据与末段标志）。 */
    void downloadSegment(int nodeId, int toggle, byte[] data, int offset, int length,
                         boolean last) {
        byte[] frame = new byte[8];
        int unused = 7 - length;
        frame[0] = (byte) (((toggle & 0x01) << 4) | (unused << 1) | (last ? 0x01 : 0x00));
        System.arraycopy(data, offset, frame, 1, length);
        send(nodeId, frame);
    }

    /** 中止帧（0x80）。 */
    void abort(int nodeId, int index, int subindex, int abortCode) {
        byte[] frame = new byte[8];
        frame[0] = (byte) 0x80;
        putIndex(frame, index, subindex);
        frame[4] = (byte) (abortCode & 0xFF);
        frame[5] = (byte) ((abortCode >>> 8) & 0xFF);
        frame[6] = (byte) ((abortCode >>> 16) & 0xFF);
        frame[7] = (byte) ((abortCode >>> 24) & 0xFF);
        send(nodeId, frame);
    }

    List<CanFrame> responses() {
        synchronized (responses) {
            return new ArrayList<>(responses);
        }
    }

    CanFrame lastResponse() {
        synchronized (responses) {
            return responses.isEmpty() ? null : responses.get(responses.size() - 1);
        }
    }

    int responseCount() {
        synchronized (responses) {
            return responses.size();
        }
    }

    void clearResponses() {
        synchronized (responses) {
            responses.clear();
        }
    }

    private static void putIndex(byte[] data, int index, int subindex) {
        data[1] = (byte) (index & 0xFF);
        data[2] = (byte) ((index >> 8) & 0xFF);
        data[3] = (byte) subindex;
    }
}
