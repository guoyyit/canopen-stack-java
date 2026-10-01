package com.github.guoyyit.canopenstackjava.can;

/**
 * 一帧经典 CAN 报文（CANopen 数据链路层），数据不可变。
 */
public final class CanFrame {

    private final int cobId;
    private final byte[] data;
    private final long sequence;
    private final int senderNodeId;

    public CanFrame(int cobId, byte[] data, long sequence) {
        this(cobId, data, sequence, -1);
    }

    public CanFrame(int cobId, byte[] data, long sequence, int senderNodeId) {
        this.cobId = cobId;
        this.data = data == null ? new byte[0] : data.clone();
        this.sequence = sequence;
        this.senderNodeId = senderNodeId;
    }

    public int getCobId() {
        return cobId;
    }

    public byte[] getData() {
        return data.clone();
    }

    /** 直接访问内部缓冲区，仅供收包路径使用，避免拷贝。 */
    public byte[] dataBytes() {
        return data;
    }

    public int getDlc() {
        return data.length;
    }

    public long getSequence() {
        return sequence;
    }

    /** 发送方节点 ID；网络层 NMT/客户端帧为 -1。真实 CAN 控制器不接收自身 TX。 */
    public int getSenderNodeId() {
        return senderNodeId;
    }
}
