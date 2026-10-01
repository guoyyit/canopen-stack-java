package com.github.guoyyit.canopenstackjava.canopen.nmt;

import com.github.guoyyit.canopenstackjava.canopen.core.CanBus;
import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;

/**
 * NMT 主站：向总线发送状态迁移命令（COB-ID 0x000）。
 */
public class NmtMaster {

    public static final int NMT_COB_ID = 0x000;

    private final CanBus bus;

    public NmtMaster(CanBus bus) {
        this.bus = bus;
    }

    public void sendCommand(NmtCommand command, int targetNodeId) {
        if (targetNodeId < 0 || targetNodeId > 127) {
            throw new IllegalArgumentException("NMT target node id out of range: " + targetNodeId);
        }
        byte[] data = new byte[]{(byte) command.getCode(), (byte) targetNodeId};
        bus.send(0, new CanFrame(NMT_COB_ID, data));
    }
}
