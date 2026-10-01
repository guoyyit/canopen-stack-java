package com.github.guoyyit.canopenstackjava.canopen.sdo;

import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;
import com.github.guoyyit.canopenstackjava.canopen.od.DataType;
import com.github.guoyyit.canopenstackjava.canopen.od.OdEntry;
import com.github.guoyyit.canopenstackjava.canopen.od.OdException;

/**
 * SDO 载荷与对象字典值之间的转换。数值采用小端序；DOMAIN/VISIBLE_STRING 直接承载字节。
 */
final class SdoCodec {

    private SdoCodec() {
    }

    static byte[] encodeEntry(OdEntry entry) {
        Object value = entry.getValue();
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof Number num) {
            long longValue = num.longValue();
            int bytes = minimalBytes(longValue, entry.getDataType());
            byte[] data = new byte[bytes];
            for (int i = 0; i < bytes; i++) {
                data[i] = (byte) (longValue >>> (8 * i));
            }
            return data;
        }
        throw new OdException(AbortCode.DATA_CANNOT_TRANSFERRED,
                "cannot encode value of " + entry.getDataType());
    }

    static Object decodeValue(OdEntry entry, byte[] data) {
        DataType type = entry.getDataType();
        if (type == DataType.DOMAIN || type == DataType.VISIBLE_STRING) {
            return data.clone();
        }
        long value = 0L;
        for (int i = 0; i < data.length; i++) {
            value |= (data[i] & 0xFFL) << (8 * i);
        }
        int bits = type.getBitLength();
        if (type.isSigned() && data.length > 0
                && (data[data.length - 1] & 0x80) != 0 && data.length * 8 < 64) {
            value |= -(1L << (data.length * 8));
        }
        return value;
    }

    static int minimalBytes(long value, DataType type) {
        if (type.getBitLength() > 0) {
            return type.getBitLength() / 8;
        }
        return 4;
    }

    static boolean fitsExpedited(int dataLength) {
        return dataLength <= 4;
    }
}
