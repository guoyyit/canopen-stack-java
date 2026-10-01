package com.github.guoyyit.canopenstackjava.canopen.emcy;

/**
 * 一条紧急报文记录（COB-ID 0x080+NodeID）。
 */
public record EmergencyMessage(int nodeId, long sequence, long timestampMillis,
                               int emergencyCode, byte errorRegister, byte[] manufacturerSpecific) {
}
