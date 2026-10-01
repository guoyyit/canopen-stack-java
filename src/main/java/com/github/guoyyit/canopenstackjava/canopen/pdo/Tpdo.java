package com.github.guoyyit.canopenstackjava.canopen.pdo;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;
import com.github.guoyyit.canopenstackjava.canopen.od.OdException;

import java.util.List;

/**
 * 发送 PDO：映射校验、组帧、事件/定时/同步触发与抑制时间控制。
 * 映射保存在不可变快照中，配置替换具有原子性：映射变更后绝不会再按旧映射发送。
 */
public class Tpdo {

    static final int MAX_PAYLOAD_BYTES = 8;

    private final CanOpenNode node;
    private final int number;

    private volatile PdoType type = PdoType.EVENT_DRIVEN;
    private volatile long eventTimerMillis;
    private volatile long inhibitTimeMicros;
    private volatile List<PdoMapping> mappings = List.of();

    private long lastTimerFireMillis = Long.MIN_VALUE;
    private long lastSentMillis = Long.MIN_VALUE;
    private boolean syncPending;
    private boolean timerArmed;

    public Tpdo(CanOpenNode node, int number) {
        this.node = node;
        this.number = number;
    }

    public int getNumber() {
        return number;
    }

    public int getCobId() {
        Object value = node.getObjectDictionary().read(0x1800 + number, 1);
        return value instanceof Number num ? num.intValue() : 0x180 + number * 0x100 + node.getNodeId();
    }

    public void configure(PdoType type, long eventTimerMillis, long inhibitTimeMicros,
                          List<PdoMapping> mappings) {
        validateMappings(mappings);
        // 先清空待发送标记与定时基准，再原子替换配置。
        this.syncPending = false;
        this.lastTimerFireMillis = Long.MIN_VALUE;
        this.lastSentMillis = Long.MIN_VALUE;
        this.timerArmed = false;
        this.type = type;
        this.eventTimerMillis = Math.max(0L, eventTimerMillis);
        this.inhibitTimeMicros = Math.max(0L, inhibitTimeMicros);
        this.mappings = List.copyOf(mappings);
        this.timerArmed = this.eventTimerMillis > 0L;
        this.lastTimerFireMillis = node.getClock().nowMillis();
        writeMappingToOd();
        node.getObjectDictionary().write(0x1800 + number, 3, this.inhibitTimeMicros / 100L);
        node.getObjectDictionary().write(0x1800 + number, 5, this.eventTimerMillis);
    }

    public void triggerEvent() {
        if (type != PdoType.EVENT_DRIVEN) {
            return;
        }
        sendIfPermitted(node.getClock().nowMillis());
    }

    public void onSync() {
        if (type != PdoType.SYNCHRONOUS || mappings.isEmpty()) {
            return;
        }
        syncPending = true;
        sendIfPermitted(node.getClock().nowMillis());
    }

    public void onTick(long nowMillis) {
        if (type == PdoType.TIMER_DRIVEN && eventTimerMillis > 0L && !mappings.isEmpty()) {
            if (timerArmed && nowMillis - lastTimerFireMillis >= eventTimerMillis) {
                lastTimerFireMillis = nowMillis;
                sendIfPermitted(nowMillis);
            }
        }
    }

    public void onLeaveOperational() {
        syncPending = false;
        lastTimerFireMillis = Long.MIN_VALUE;
        timerArmed = false;
    }

    public void clearMapping() {
        mappings = List.of();
        syncPending = false;
        writeMappingToOd();
    }

    private void sendIfPermitted(long nowMillis) {
        List<PdoMapping> snapshot = mappings;
        if (snapshot.isEmpty() || node.getState() != NmtState.OPERATIONAL) {
            return;
        }
        if (lastSentMillis != Long.MIN_VALUE && inhibitTimeMicros > 0L) {
            long elapsedMicros = (nowMillis - lastSentMillis) * 1000L;
            if (elapsedMicros < inhibitTimeMicros) {
                return;
            }
        }
        byte[] payload = new byte[payloadBytes(snapshot)];
        int bitOffset = 0;
        for (PdoMapping mapping : snapshot) {
            PdoCodec.pack(node.getObjectDictionary(), mapping, payload, bitOffset);
            bitOffset += mapping.bitLength();
        }
        node.getBus().send(node.getNodeId(), new CanFrame(getCobId(), payload));
        lastSentMillis = nowMillis;
        syncPending = false;
    }

    private void validateMappings(List<PdoMapping> requested) {
        int totalBits = 0;
        for (PdoMapping mapping : requested) {
            if (mapping.bitLength() <= 0 || mapping.bitLength() % 8 != 0) {
                throw new OdException(AbortCode.PDO_MAP_INVALID,
                        "PDO mapping bit length must be a positive multiple of 8: " + mapping);
            }
            if (!node.getObjectDictionary().exists(mapping.index(), mapping.subindex())) {
                throw new OdException(AbortCode.PDO_MAP_INVALID,
                        String.format("mapped object 0x%04X:%02X does not exist",
                                mapping.index(), mapping.subindex()));
            }
            totalBits += mapping.bitLength();
        }
        if (totalBits > MAX_PAYLOAD_BYTES * 8) {
            throw new OdException(AbortCode.PDO_LENGTH_EXCEEDED,
                    "TPDO " + number + " mapped length " + totalBits
                            + " bits exceeds " + MAX_PAYLOAD_BYTES * 8);
        }
    }

    private static int payloadBytes(List<PdoMapping> snapshot) {
        int totalBits = 0;
        for (PdoMapping mapping : snapshot) {
            totalBits += mapping.bitLength();
        }
        return totalBits / 8;
    }

    private void writeMappingToOd() {
        int mapIndex = 0x1A00 + number;
        List<PdoMapping> snapshot = mappings;
        node.getObjectDictionary().write(mapIndex, 0, 0L);
        for (int sub = 1; sub <= 8; sub++) {
            long encoded = sub <= snapshot.size() ? snapshot.get(sub - 1).encode() & 0xFFFFFFFFL : 0L;
            node.getObjectDictionary().write(mapIndex, sub, encoded);
        }
        node.getObjectDictionary().write(mapIndex, 0, (long) snapshot.size());
    }
}
