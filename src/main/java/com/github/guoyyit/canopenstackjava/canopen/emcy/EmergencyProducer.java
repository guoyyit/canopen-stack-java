package com.github.guoyyit.canopenstackjava.canopen.emcy;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState;

/**
 * 紧急报文生产者（COB-ID 0x080+NodeID）。
 * 错误事件发生时：更新错误寄存器 0x1001、将记录写入历史 0x1003 并发送 EMCY 帧。
 * 历史与错误寄存器在任意状态更新；EMCY 帧仅在 PRE-OP/OP 发送，INIT/STOPPED 被抑制，
 * 但记录不会丢失，恢复通信后仍可通过 0x1003 查询。
 */
public class EmergencyProducer {

    /** 通用错误位（CiA 301 0x1001 bit0）。 */
    public static final byte GENERIC_ERROR_BIT = 0x01;

    private final CanOpenNode node;
    private final EmergencyHistory history;
    private byte errorRegister;
    private long sequence;

    public EmergencyProducer(CanOpenNode node, EmergencyHistory history) {
        this.node = node;
        this.history = history;
    }

    public void raiseError(int emergencyCode, byte manufacturerSpecific0to4) {
        errorRegister |= GENERIC_ERROR_BIT;
        syncErrorRegister();
        EmergencyMessage message = new EmergencyMessage(
                node.getNodeId(),
                ++sequence,
                node.getClock().nowMillis(),
                emergencyCode & 0xFFFF,
                errorRegister,
                manufacturerBytes(manufacturerSpecific0to4));
        history.append(message);
        if (node.getState() == NmtState.PRE_OPERATIONAL
                || node.getState() == NmtState.OPERATIONAL) {
            byte[] payload = new byte[8];
            payload[0] = (byte) (emergencyCode & 0xFF);
            payload[1] = (byte) ((emergencyCode >> 8) & 0xFF);
            payload[2] = errorRegister;
            byte[] specific = message.manufacturerSpecific();
            System.arraycopy(specific, 0, payload, 3, specific.length);
            node.getBus().send(node.getNodeId(),
                    new CanFrame(0x080 + node.getNodeId(), payload));
        }
    }

    public void raiseError(int emergencyCode) {
        raiseError(emergencyCode, (byte) 0);
    }

    public byte getErrorRegister() {
        return errorRegister;
    }

    public EmergencyHistory getHistory() {
        return history;
    }

    public long getSequence() {
        return sequence;
    }

    /** 反序列化恢复时重建内部序号与错误寄存器，不触发新报文。 */
    public void restoreState(byte errorRegister, long sequence) {
        this.errorRegister = errorRegister;
        this.sequence = sequence;
        syncErrorRegister();
    }

    /**
     * 清空错误历史与错误寄存器（对应向 0x1003:0 写 0 的 CiA 301 语义）。
     * 清空后历史、对外读出条数与错误寄存器一致归零。
     */
    public void clearErrors() {
        history.clear();
        errorRegister = 0;
        syncErrorRegister();
    }

    private void syncErrorRegister() {
        // 只读项的自动状态同步走内部写入路径，不受对外只读限制影响。
        node.getObjectDictionary().writeInternal(0x1001, 0, (long) (errorRegister & 0xFF));
        node.getObjectDictionary().writeInternal(0x1003, 0, (long) Math.min(history.size(), 254));
        syncErrorField();
    }

    /** 将最近最多 8 条历史同步到 0x1003:1..8（最新在子索引 1），其余清零。 */
    private void syncErrorField() {
        java.util.List<EmergencyMessage> all = history.all();
        for (int sub = 1; sub <= 8; sub++) {
            long field = 0L;
            int historyIndex = all.size() - sub;
            if (historyIndex >= 0) {
                EmergencyMessage message = all.get(historyIndex);
                field = (message.emergencyCode() & 0xFFFFL)
                        | ((message.errorRegister() & 0xFFL) << 16);
            }
            node.getObjectDictionary().writeInternal(0x1003, sub, field);
        }
    }

    private static byte[] manufacturerBytes(byte pattern) {
        return new byte[]{pattern, 0, 0, 0, 0};
    }

}
