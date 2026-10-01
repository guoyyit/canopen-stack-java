package com.github.guoyyit.canopenstackjava.canopen.node;

import com.github.guoyyit.canopenstackjava.canopen.emcy.EmergencyMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点持久化快照：NMT 状态、对象字典内容与紧急报文历史。
 * 使用公共可变字段，便于 JSON 序列化/反序列化。
 */
public class NodeSnapshot {

    public int nodeId;
    public String state;
    public int heartbeatPeriodMillis;

    /** 键为 "index:subindex"，值为编码后的字节（小端序），类型另存。 */
    public Map<String, String> odValuesHex = new LinkedHashMap<>();
    public Map<String, String> odTypeNames = new LinkedHashMap<>();
    public List<EmergencyMessage> emergencies = new ArrayList<>();

    public NodeSnapshot() {
    }
}
