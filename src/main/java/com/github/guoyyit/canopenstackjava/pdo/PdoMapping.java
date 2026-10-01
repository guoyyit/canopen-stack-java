package com.github.guoyyit.canopenstackjava.pdo;

import java.util.List;

/**
 * 一个 PDO 的映射与通信参数。
 *
 * <p>映射遵循 CiA 301 的“先写子索引 0 = 0 解锁，再改映射，最后写子索引 0 = N 激活”
 * 流程。每次激活映射 {@code version} 自增，已在途/已调度的旧版本帧不再发送。
 */
public final class PdoMapping {

    private final int number;
    private final boolean transmit;

    private int cobId;
    private PdoTriggerType triggerType = PdoTriggerType.EVENT;
    private long inhibitTimeMicros;
    private long timerPeriodMillis;

    private boolean active;
    private boolean configuring;
    private List<MappingEntry> entries = List.of();
    private long version;

    public PdoMapping(int number, boolean transmit) {
        this.number = number;
        this.transmit = transmit;
    }

    public int getNumber() {
        return number;
    }

    public boolean isTransmit() {
        return transmit;
    }

    public int getCobId() {
        return cobId;
    }

    public void setCobId(int cobId) {
        this.cobId = cobId;
    }

    public int getTransmissionType() {
        switch (triggerType) {
            case SYNC:
                return 0x00;
            case TIMER:
                return 0x01;
            case EVENT:
            default:
                return 0xFF;
        }
    }

    public void setTransmissionType(int type) {
        this.triggerType = PdoTriggerType.fromCode(type);
    }

    public PdoTriggerType getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(PdoTriggerType type) {
        this.triggerType = type == null ? PdoTriggerType.EVENT : type;
    }

    public long getInhibitTimeMicros() {
        return inhibitTimeMicros;
    }

    public void setInhibitTimeMicros(long micros) {
        this.inhibitTimeMicros = micros;
    }

    public long getTimerPeriodMillis() {
        return timerPeriodMillis;
    }

    public void setTimerPeriodMillis(long millis) {
        this.timerPeriodMillis = millis;
    }

    public List<MappingEntry> getEntries() {
        return entries;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version;
    }

    /** 解锁映射（等价于写映射参数子索引 0 = 0）。 */
    public void beginConfiguration() {
        this.configuring = true;
        this.active = false;
        this.entries = List.of();
    }

    public void setMappingEntries(List<MappingEntry> newEntries) {
        if (!configuring) {
            throw new IllegalStateException("mapping is locked; write sub-index 0 = 0 first");
        }
        this.entries = List.copyOf(newEntries);
    }

    /** 激活映射（等价于写子索引 0 = 条目数）。条目数必须与声明一致。 */
    public void activate() {
        activate(this.entries.size());
    }

    public void activate(int declaredCount) {
        if (!configuring) {
            throw new IllegalStateException("mapping is locked; write sub-index 0 = 0 first");
        }
        if (declaredCount != entries.size()) {
            throw new IllegalArgumentException(
                    "declared count " + declaredCount + " != entries " + entries.size());
        }
        this.configuring = false;
        this.active = true;
        this.version++;
    }

    /** 通信复位：去激活并回到初始未映射状态。 */
    public void deactivate() {
        this.active = false;
        this.configuring = false;
        this.entries = List.of();
        this.version++;
    }
}
