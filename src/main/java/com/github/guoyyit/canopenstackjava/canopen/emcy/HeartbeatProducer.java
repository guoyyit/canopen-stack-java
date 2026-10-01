package com.github.guoyyit.canopenstackjava.canopen.emcy;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;

/**
 * 心跳生产者：按 0x1017 周期发送心跳帧（COB-ID 0x700+NodeID，数据为 NMT 状态字节）。
 */
public class HeartbeatProducer {

    private final CanOpenNode node;
    private long lastSentMillis = Long.MIN_VALUE;

    public HeartbeatProducer(CanOpenNode node) {
        this.node = node;
    }

    public void onTick(long nowMillis) {
        long period = getPeriodMillis();
        if (period <= 0L) {
            return;
        }
        if (lastSentMillis == Long.MIN_VALUE || nowMillis - lastSentMillis >= period) {
            sendHeartbeat(nowMillis);
        }
    }

    public void onStateChanged(long nowMillis) {
        if (getPeriodMillis() > 0L && node.isRunning()) {
            sendHeartbeat(nowMillis);
        }
    }

    public long getPeriodMillis() {
        Object period = node.getObjectDictionary().read(0x1017, 0);
        return period instanceof Number num ? num.longValue() : 0L;
    }

    private void sendHeartbeat(long nowMillis) {
        byte[] data = new byte[]{(byte) node.getState().getCode()};
        node.getBus().send(node.getNodeId(),
                new CanFrame(0x700 + node.getNodeId(), data));
        lastSentMillis = nowMillis;
    }
}
