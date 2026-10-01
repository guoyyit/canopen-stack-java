package com.github.guoyyit.canopenstackjava.canopen.pdo;

import com.github.guoyyit.canopenstackjava.canopen.od.DataType;
import com.github.guoyyit.canopenstackjava.canopen.od.ObjectDictionary;

/**
 * PDO 载荷与对象字典之间的小端序编解码。
 */
final class PdoCodec {

    private PdoCodec() {
    }

    static void pack(ObjectDictionary od, PdoMapping mapping, byte[] payload, int bitOffset) {
        DataType type = od.entry(mapping.index(), mapping.subindex()).getDataType();
        int byteOffset = bitOffset / 8;
        Object value = od.read(mapping.index(), mapping.subindex());
        if (value instanceof Number num) {
            long longValue = num.longValue();
            int byteCount = mapping.bitLength() / 8;
            for (int i = 0; i < byteCount; i++) {
                payload[byteOffset + i] = (byte) (longValue >>> (8 * i));
            }
        } else if (value instanceof byte[] bytes) {
            int byteCount = mapping.bitLength() / 8;
            System.arraycopy(bytes, 0, payload, byteOffset,
                    Math.min(byteCount, bytes.length));
        }
    }

    static void unpack(ObjectDictionary od, PdoMapping mapping, byte[] payload, int bitOffset) {
        int byteOffset = bitOffset / 8;
        int byteCount = mapping.bitLength() / 8;
        DataType type = od.entry(mapping.index(), mapping.subindex()).getDataType();
        if (type.getBitLength() > 0) {
            long value = 0L;
            for (int i = 0; i < byteCount; i++) {
                value |= (payload[byteOffset + i] & 0xFFL) << (8 * i);
            }
            od.write(mapping.index(), mapping.subindex(), value);
        } else {
            byte[] bytes = new byte[byteCount];
            System.arraycopy(payload, byteOffset, bytes, 0, byteCount);
            od.write(mapping.index(), mapping.subindex(), bytes);
        }
    }
}
