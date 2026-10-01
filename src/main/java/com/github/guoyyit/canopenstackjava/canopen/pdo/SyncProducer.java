package com.github.guoyyit.canopenstackjava.canopen.pdo;

import com.github.guoyyit.canopenstackjava.canopen.core.CanBus;
import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;

/**
 * SYNC 生产者（COB-ID 0x080，零数据长度），周期以毫秒为单位（0x1006 以微秒为单位）。
 */
public class SyncProducer {

    public static final int SYNC_COB_ID = 0x080;

    private final CanBus bus;
    private final int producerNodeId;

    private long cycleMillis;
    private long lastSentMillis = Long.MIN_VALUE;

    public SyncProducer(CanBus bus, int producerNodeId) {
        this.bus = bus;
        this.producerNodeId = producerNodeId;
    }

    public void setCycleMillis(long cycleMillis) {
        this.cycleMillis = Math.max(0L, cycleMillis);
        this.lastSentMillis = Long.MIN_VALUE;
    }

    public void sendSync() {
        bus.send(producerNodeId, new CanFrame(SYNC_COB_ID, new byte[0]));
    }

    public void onTick(long nowMillis) {
        if (cycleMillis <= 0L) {
            return;
        }
        if (lastSentMillis == Long.MIN_VALUE
                || nowMillis - lastSentMillis >= cycleMillis) {
            lastSentMillis = nowMillis;
            sendSync();
        }
    }
}
