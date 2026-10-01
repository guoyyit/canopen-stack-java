package com.github.guoyyit.canopenstackjava.pdo;

import com.github.guoyyit.canopenstackjava.od.AbortCode;
import com.github.guoyyit.canopenstackjava.od.OdAccessException;
import com.github.guoyyit.canopenstackjava.od.ObjectDictionary;

import java.util.List;

/**
 * 单个 PDO 通道：按映射在对象字典与 8 字节负载间打包 / 解包。
 * 映射项按字节连续布局（位长度必须是 8 的倍数，总长度不超过 64 位）。
 */
final class PdoChannel {

    private final PdoMapping mapping;
    private final ObjectDictionary od;

    PdoChannel(PdoMapping mapping, ObjectDictionary od) {
        this.mapping = mapping;
        this.od = od;
    }

    PdoMapping getMapping() {
        return mapping;
    }

    /** 按当前映射版本从 OD 打包；条目缺失 / 不可读 / 超长时抛标准中止码。 */
    byte[] pack() {
        List<MappingEntry> entries = mapping.getEntries();
        int totalBits = totalBits(entries);
        if (totalBits <= 0) {
            throw new OdAccessException(AbortCode.CANNOT_MAP_TO_PDO, "empty PDO mapping");
        }
        byte[] payload = new byte[totalBits / 8];
        int bitOffset = 0;
        for (MappingEntry entry : entries) {
            if (!od.contains(entry.index(), entry.subIndex())) {
                throw new OdAccessException(AbortCode.OBJECT_DOES_NOT_EXIST,
                        "mapped object 0x" + Integer.toHexString(entry.index()) + " missing");
            }
            byte[] value = od.read(entry.index(), entry.subIndex());
            int entryBytes = entry.bitLength() / 8;
            if (value.length < entryBytes) {
                throw new OdAccessException(AbortCode.CANNOT_MAP_TO_PDO,
                        "object shorter than mapped length");
            }
            int byteOffset = bitOffset / 8;
            System.arraycopy(value, 0, payload, byteOffset, entryBytes);
            bitOffset += entry.bitLength();
        }
        return payload;
    }

    /** 按当前映射版本解包进 OD；负载长度不符按 PDO 长度错误拒绝。 */
    void unpack(byte[] data) {
        List<MappingEntry> entries = mapping.getEntries();
        int expectedBits = totalBits(entries);
        if (data == null || data.length * 8 != expectedBits) {
            throw new OdAccessException(AbortCode.PDO_LENGTH_EXCEEDED,
                    "PDO payload length does not match active mapping");
        }
        int bitOffset = 0;
        for (MappingEntry entry : entries) {
            int entryBytes = entry.bitLength() / 8;
            byte[] value = new byte[entryBytes];
            System.arraycopy(data, bitOffset / 8, value, 0, entryBytes);
            od.write(entry.index(), entry.subIndex(), value);
            bitOffset += entry.bitLength();
        }
    }

    static int totalBits(List<MappingEntry> entries) {
        int total = 0;
        for (MappingEntry entry : entries) {
            if (entry.bitLength() % 8 != 0) {
                throw new OdAccessException(AbortCode.CANNOT_MAP_TO_PDO,
                        "only byte-aligned mappings supported");
            }
            total += entry.bitLength();
        }
        if (total > 64) {
            throw new OdAccessException(AbortCode.PDO_LENGTH_EXCEEDED,
                    "mapped total " + total + " bits exceeds 64 bits");
        }
        return total;
    }
}
