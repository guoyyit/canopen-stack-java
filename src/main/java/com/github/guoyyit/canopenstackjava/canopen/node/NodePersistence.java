package com.github.guoyyit.canopenstackjava.canopen.node;

import com.github.guoyyit.canopenstackjava.canopen.emcy.EmergencyMessage;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.canopen.od.DataType;
import com.github.guoyyit.canopenstackjava.canopen.od.OdEntry;
import com.github.guoyyit.canopenstackjava.canopen.od.OdKey;

import java.util.HexFormat;

/**
 * 节点快照的编码/解码与恢复。
 *
 * <p>持久化内容：NMT 状态、0x2000 应用区对象字典值（含变长字节）、紧急报文历史。
 * 通信区（0x1xxx）与 PDO 映射（0x14xx/0x16xx/0x18xx/0x1Axx）不持久化，
 * 重启后按标准默认值重建。恢复时强制终止任何进行中的 SDO 分段传输。</p>
 */
final class NodePersistence {

    private NodePersistence() {
    }

    static NodeSnapshot snapshot(CanOpenNode node) {
        NodeSnapshot snapshot = new NodeSnapshot();
        snapshot.nodeId = node.getNodeId();
        snapshot.state = node.getState().name();
        Object heartbeat = node.getObjectDictionary().read(0x1017, 0);
        snapshot.heartbeatPeriodMillis = heartbeat instanceof Number num ? num.intValue() : 0;

        for (OdKey key : node.getObjectDictionary().keys()) {
            if (!isPersistent(key.index())) {
                continue;
            }
            OdEntry entry = node.getObjectDictionary().entry(key.index(), key.subindex());
            Object value = entry.getValue();
            byte[] encoded = encodeValue(value, entry.getDataType());
            String mapKey = key.index() + ":" + key.subindex();
            snapshot.odValuesHex.put(mapKey, HexFormat.of().formatHex(encoded));
            snapshot.odTypeNames.put(mapKey, entry.getDataType().name());
        }
        snapshot.emergencies = node.getEmergencyHistory().all();
        return snapshot;
    }

    static void restore(CanOpenNode node, NodeSnapshot snapshot) {
        // 恢复前终止全部 SDO 分段传输会话，确保重启后不存在悬挂传输。
        node.getSdoServer().abortAllSessions();
        for (int i = 0; i < CanOpenNode.PDO_CHANNEL_COUNT; i++) {
            node.tpdo(i).clearMapping();
            node.rpdo(i).clearMapping();
        }

        for (var mapEntry : snapshot.odValuesHex.entrySet()) {
            String[] parts = mapEntry.getKey().split(":");
            int index = Integer.parseInt(parts[0]);
            int subindex = Integer.parseInt(parts[1]);
            if (!node.getObjectDictionary().exists(index, subindex)) {
                continue;
            }
            DataType type = DataType.valueOf(snapshot.odTypeNames.get(mapEntry.getKey()));
            byte[] raw = HexFormat.of().parseHex(mapEntry.getValue());
            node.getObjectDictionary().write(index, subindex, decodeValue(raw, type));
        }

        for (EmergencyMessage message : snapshot.emergencies) {
            node.getEmergencyHistory().append(message);
        }
        if (!snapshot.emergencies.isEmpty()) {
            EmergencyMessage latest = snapshot.emergencies.get(snapshot.emergencies.size() - 1);
            node.getEmergencyProducer().restoreState(
                    latest.errorRegister(), latest.sequence());
        }
        if (snapshot.heartbeatPeriodMillis > 0) {
            node.getObjectDictionary().write(0x1017, 0, (long) snapshot.heartbeatPeriodMillis);
        }
        NmtState restoredState;
        try {
            restoredState = NmtState.valueOf(snapshot.state);
        } catch (RuntimeException ex) {
            restoredState = NmtState.PRE_OPERATIONAL;
        }
        // OPERATIONAL 依赖已重新建立的通信，恢复时落到 PRE-OPERATIONAL 由主站重新置 OP。
        node.enterState(restoredState == NmtState.OPERATIONAL
                ? NmtState.PRE_OPERATIONAL : restoredState);
    }

    private static boolean isPersistent(int index) {
        // 0x1001/0x1003 由紧急历史恢复逻辑推导，不直接持久化其只读子索引。
        return (index >= 0x2000 && index <= 0x5FFF) || index == 0x1017;
    }

    private static byte[] encodeValue(Object value, DataType type) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        long longValue = value instanceof Number num ? num.longValue() : 0L;
        int byteCount = Math.max(1, type.getBitLength() / 8);
        byte[] result = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            result[i] = (byte) (longValue >>> (8 * i));
        }
        return result;
    }

    private static Object decodeValue(byte[] raw, DataType type) {
        if (type.getBitLength() == 0) {
            return raw;
        }
        long value = 0L;
        for (int i = 0; i < raw.length; i++) {
            value |= (raw[i] & 0xFFL) << (8 * i);
        }
        return value;
    }
}
