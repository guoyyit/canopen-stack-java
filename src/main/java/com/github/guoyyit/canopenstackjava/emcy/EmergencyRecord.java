package com.github.guoyyit.canopenstackjava.emcy;

/** 一条紧急报文记录（错误码 + 错误寄存器 + 厂商字段）。 */
public final class EmergencyRecord {

    private final long timestampMillis;
    private final int errorCode;
    private final int errorRegister;
    private final byte[] manufacturerField;

    public EmergencyRecord(long timestampMillis, int errorCode, int errorRegister, byte[] manufacturerField) {
        this.timestampMillis = timestampMillis;
        this.errorCode = errorCode;
        this.errorRegister = errorRegister;
        this.manufacturerField = manufacturerField == null ? new byte[0] : manufacturerField.clone();
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public int getErrorRegister() {
        return errorRegister;
    }

    public byte[] getManufacturerField() {
        return manufacturerField.clone();
    }
}
