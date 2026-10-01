package com.github.guoyyit.canopenstackjava.persist;

import com.github.guoyyit.canopenstackjava.nmt.NmtState;

import java.util.List;

/** 单个节点重启恢复快照：NMT 状态 + OD 数据 + EMCY 历史。 */
public final class NodeSnapshot {

    private int nodeId;
    private NmtState state;
    private List<EntrySnapshot> entries;
    private EmergencySnapshot emergency;

    public NodeSnapshot() {
    }

    public int getNodeId() {
        return nodeId;
    }

    public void setNodeId(int nodeId) {
        this.nodeId = nodeId;
    }

    public NmtState getState() {
        return state;
    }

    public void setState(NmtState state) {
        this.state = state;
    }

    public List<EntrySnapshot> getEntries() {
        return entries;
    }

    public void setEntries(List<EntrySnapshot> entries) {
        this.entries = entries;
    }

    public EmergencySnapshot getEmergency() {
        return emergency;
    }

    public void setEmergency(EmergencySnapshot emergency) {
        this.emergency = emergency;
    }
}
