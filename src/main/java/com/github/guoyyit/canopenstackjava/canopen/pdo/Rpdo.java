package com.github.guoyyit.canopenstackjava.canopen.pdo;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;
import com.github.guoyyit.canopenstackjava.canopen.od.OdException;

import java.util.List;

/**
 * 接收 PDO：按当前不可变映射快照将收到的报文拆包写入对象字典。
 * 映射变更对正在处理的帧无影响（处理开始时抓取一次快照）。
 */
public class Rpdo {

    private final CanOpenNode node;
    private final int number;

    private volatile List<PdoMapping> mappings = List.of();

    public Rpdo(CanOpenNode node, int number) {
        this.node = node;
        this.number = number;
    }

    public int getNumber() {
        return number;
    }

    public int getCobId() {
        Object value = node.getObjectDictionary().read(0x1400 + number, 1);
        return value instanceof Number num ? num.intValue()
                : 0x200 + number * 0x100 + node.getNodeId();
    }

    public void configure(List<PdoMapping> mappings) {
        validateMappings(mappings);
        this.mappings = List.copyOf(mappings);
        writeMappingToOd();
    }

    public boolean handles(int cobId) {
        return !mappings.isEmpty() && cobId == getCobId();
    }

    public void onFrame(CanFrame frame) {
        List<PdoMapping> snapshot = mappings;
        if (snapshot.isEmpty() || frame.getId() != getCobId()) {
            return;
        }
        int expectedBytes = 0;
        for (PdoMapping mapping : snapshot) {
            expectedBytes += mapping.bitLength() / 8;
        }
        if (frame.getDataLength() < expectedBytes) {
            node.getEmergencyProducer().raiseError(0x8220);
            return;
        }
        byte[] payload = frame.getData();
        int bitOffset = 0;
        for (PdoMapping mapping : snapshot) {
            PdoCodec.unpack(node.getObjectDictionary(), mapping, payload, bitOffset);
            bitOffset += mapping.bitLength();
        }
    }

    public void clearMapping() {
        mappings = List.of();
        writeMappingToOd();
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
        if (totalBits > Tpdo.MAX_PAYLOAD_BYTES * 8) {
            throw new OdException(AbortCode.PDO_LENGTH_EXCEEDED,
                    "RPDO " + number + " mapped length " + totalBits
                            + " bits exceeds " + Tpdo.MAX_PAYLOAD_BYTES * 8);
        }
    }

    private void writeMappingToOd() {
        int mapIndex = 0x1600 + number;
        List<PdoMapping> snapshot = mappings;
        node.getObjectDictionary().write(mapIndex, 0, 0L);
        for (int sub = 1; sub <= 8; sub++) {
            long encoded = sub <= snapshot.size() ? snapshot.get(sub - 1).encode() & 0xFFFFFFFFL : 0L;
            node.getObjectDictionary().write(mapIndex, sub, encoded);
        }
        node.getObjectDictionary().write(mapIndex, 0, (long) snapshot.size());
    }
}
