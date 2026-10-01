package com.github.guoyyit.canopenstackjava.canopen.core;

/**
 * 一帧经典 CAN 报文（11 位标识，数据长度 0~8 字节）。
 */
public final class CanFrame {

    private final int id;
    private final byte[] data;

    public CanFrame(int id, byte[] data) {
        if (id < 0 || id > 0x7FF) {
            throw new IllegalArgumentException("CAN id out of 11-bit range: " + id);
        }
        if (data == null) {
            data = new byte[0];
        }
        if (data.length > 8) {
            throw new IllegalArgumentException("classic CAN data length must be <= 8");
        }
        this.id = id;
        this.data = data.clone();
    }

    public int getId() {
        return id;
    }

    public byte[] getData() {
        return data.clone();
    }

    public int getDataLength() {
        return data.length;
    }

    public int getU8(int offset) {
        return data[offset] & 0xFF;
    }
}
