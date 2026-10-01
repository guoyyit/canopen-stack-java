package com.github.guoyyit.canopenstackjava.emcy;

import com.github.guoyyit.canopenstackjava.can.CanBus;
import com.github.guoyyit.canopenstackjava.can.CanFrame;

import java.util.HashSet;
import java.util.Set;

/**
 * EMCY 生产者：错误发生时发送 0x080+nodeId 帧并累积历史。
 * 同一错误码去重（错误消失前不重复发送）。
 */
public final class EmergencyService {

    private static final int GENERIC_ERROR_BIT = 0x01;

    private final int nodeId;
    private final CanBus bus;
    private final EmergencyHistory history;
    private final Set<Integer> activeErrors = new HashSet<>();
    private volatile boolean communicationEnabled = true;

    public EmergencyService(int nodeId, CanBus bus, EmergencyHistory history) {
        this.nodeId = nodeId;
        this.bus = bus;
        this.history = history;
    }

    public synchronized void raise(int errorCode, byte[] manufacturerField) {
        boolean added = activeErrors.add(errorCode);
        EmergencyRecord record = new EmergencyRecord(System.currentTimeMillis(), errorCode,
                errorRegisterLocked(), manufacturerField);
        history.add(record);
        if (added && communicationEnabled) {
            sendFrame(errorCode, manufacturerField);
        }
    }

    public synchronized void clearError(int errorCode) {
        if (activeErrors.remove(errorCode) && communicationEnabled) {
            // 错误清零发送 error-free EMCY（错误码 0x0000，CiA 301）
            sendFrame(0x0000, new byte[5]);
        }
    }

    public synchronized int getErrorRegister() {
        return errorRegisterLocked();
    }

    public EmergencyHistory getHistory() {
        return history;
    }

    /** 通信复位：历史与活跃错误保留（属应用层持久数据），仅暂停/恢复发帧。 */
    public synchronized void onCommunicationReset() {
        communicationEnabled = true;
    }

    synchronized Set<Integer> snapshotActiveErrors() {
        return new HashSet<>(activeErrors);
    }

    synchronized void restore(Set<Integer> errors) {
        activeErrors.clear();
        activeErrors.addAll(errors);
    }

    private int errorRegisterLocked() {
        return activeErrors.isEmpty() ? 0 : GENERIC_ERROR_BIT;
    }

    private void sendFrame(int errorCode, byte[] manufacturerField) {
        byte[] payload = new byte[8];
        payload[0] = (byte) errorCode;
        payload[1] = (byte) (errorCode >>> 8);
        payload[2] = (byte) errorRegisterLocked();
        byte[] field = manufacturerField == null ? new byte[5] : manufacturerField;
        for (int i = 0; i < 5; i++) {
            payload[3 + i] = i < field.length ? field[i] : 0;
        }
        bus.send(new CanFrame(0x080 + (nodeId & 0x7F), payload, 0));
    }
}
