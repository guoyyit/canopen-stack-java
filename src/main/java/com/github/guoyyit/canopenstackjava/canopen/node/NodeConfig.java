package com.github.guoyyit.canopenstackjava.canopen.node;

/**
 * 节点静态配置参数。
 */
public class NodeConfig {

    private int heartbeatPeriodMillis = 1000;
    private long sdoTimeoutMillis = 3000;

    public int getHeartbeatPeriodMillis() {
        return heartbeatPeriodMillis;
    }

    public void setHeartbeatPeriodMillis(int heartbeatPeriodMillis) {
        this.heartbeatPeriodMillis = heartbeatPeriodMillis;
    }

    public long getSdoTimeoutMillis() {
        return sdoTimeoutMillis;
    }

    public void setSdoTimeoutMillis(long sdoTimeoutMillis) {
        this.sdoTimeoutMillis = sdoTimeoutMillis;
    }
}
