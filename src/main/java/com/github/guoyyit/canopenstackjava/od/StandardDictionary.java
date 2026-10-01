package com.github.guoyyit.canopenstackjava.od;

/** 构造 CiA 301 最小标准对象字典（设备信息、错误寄存器、通信参数占位等）。 */
public final class StandardDictionary {

    private StandardDictionary() {
    }

    public static ObjectDictionary create(int nodeId) {
        ObjectDictionary od = new ObjectDictionary();
        add(od, 0x1000, 0, DataType.UNSIGNED32, AccessType.RO, u32(0), null, null);
        add(od, 0x1001, 0, DataType.UNSIGNED8, AccessType.RO, u8(0), 0L, 0xFFL);
        add(od, 0x1003, 0, DataType.UNSIGNED8, AccessType.RW, u8(0), 0L, 254L);
        add(od, 0x1008, 0, DataType.VISIBLE_STRING, AccessType.RO,
                "CANopen Sim Node".getBytes(java.nio.charset.StandardCharsets.US_ASCII), null, null);
        add(od, 0x100B, 0, DataType.VISIBLE_STRING, AccessType.RO, new byte[0], null, null);
        add(od, 0x1017, 0, DataType.UNSIGNED16, AccessType.RW, u16(0), 0L, 65535L);
        add(od, 0x1018, 0, DataType.UNSIGNED8, AccessType.RO, u8(4), null, null);
        add(od, 0x1018, 1, DataType.UNSIGNED32, AccessType.RO, u32(0x00000000), null, null);
        add(od, 0x1018, 2, DataType.UNSIGNED32, AccessType.RO, u32(0x00000000), null, null);
        add(od, 0x1018, 3, DataType.UNSIGNED32, AccessType.RO, u32(0x00000001), null, null);
        add(od, 0x1018, 4, DataType.UNSIGNED32, AccessType.RO, u32(nodeId), null, null);

        for (int i = 0; i < 4; i++) {
            addRpdos(od, i, nodeId);
            addTpdos(od, i, nodeId);
        }

        // 应用过程数据：事件 TPDO 默认映射 0x2001，RPDO 默认映射 0x2000
        add(od, 0x2000, 0, DataType.UNSIGNED16, AccessType.RW, u16(0), 0L, 65535L);
        add(od, 0x2001, 0, DataType.UNSIGNED16, AccessType.RW, u16(0), 0L, 65535L);
        add(od, 0x2002, 0, DataType.UNSIGNED32, AccessType.RW, u32(0), null, null);
        return od;
    }

    /** 通信复位时把通信参数恢复为上电默认值。 */
    public static void resetCommunication(ObjectDictionary od) {
        for (int i = 0; i < 4; i++) {
            int rComm = 0x1400 + i;
            od.write(rComm, 1, u32(defaultRpdoCobId(i, 0)));
            od.write(rComm, 2, u8(0xFF));
            int tComm = 0x1800 + i;
            od.write(tComm, 1, u32(defaultTpdoCobId(i, 0)));
            od.write(tComm, 2, u8(i == 0 ? 0xFF : 0xFE));
            od.write(tComm, 3, u16(0));
            od.write(tComm, 5, u16(0));
            resetMapping(od, 0x1600 + i);
            resetMapping(od, 0x1A00 + i);
        }
        od.write(0x1017, 0, u16(0));
    }

    private static void addRpdos(ObjectDictionary od, int i, int nodeId) {
        int comm = 0x1400 + i;
        add(od, comm, 0, DataType.UNSIGNED8, AccessType.RO, u8(2), null, null);
        add(od, comm, 1, DataType.UNSIGNED32, AccessType.RW, u32(defaultRpdoCobId(i, nodeId)), null, null);
        add(od, comm, 2, DataType.UNSIGNED8, AccessType.RW, u8(0xFF), 0L, 255L);
        int map = 0x1600 + i;
        add(od, map, 0, DataType.UNSIGNED8, AccessType.RW, u8(0), 0L, 8L);
        add(od, map, 1, DataType.UNSIGNED32, AccessType.RW, u32(0), null, null);
        add(od, map, 2, DataType.UNSIGNED32, AccessType.RW, u32(0), null, null);
        add(od, map, 3, DataType.UNSIGNED32, AccessType.RW, u32(0), null, null);
        add(od, map, 4, DataType.UNSIGNED32, AccessType.RW, u32(0), null, null);
        add(od, map, 5, DataType.UNSIGNED32, AccessType.RW, u32(0), null, null);
        add(od, map, 6, DataType.UNSIGNED32, AccessType.RW, u32(0), null, null);
        add(od, map, 7, DataType.UNSIGNED32, AccessType.RW, u32(0), null, null);
        add(od, map, 8, DataType.UNSIGNED32, AccessType.RW, u32(0), null, null);
    }

    private static void addTpdos(ObjectDictionary od, int i, int nodeId) {
        int comm = 0x1800 + i;
        add(od, comm, 0, DataType.UNSIGNED8, AccessType.RO, u8(5), null, null);
        add(od, comm, 1, DataType.UNSIGNED32, AccessType.RW, u32(defaultTpdoCobId(i, nodeId)), null, null);
        add(od, comm, 2, DataType.UNSIGNED8, AccessType.RW, u8(i == 0 ? 0xFF : 0xFE), 0L, 255L);
        add(od, comm, 3, DataType.UNSIGNED16, AccessType.RW, u16(0), 0L, 65535L);
        add(od, comm, 5, DataType.UNSIGNED16, AccessType.RW, u16(0), 0L, 65535L);
        int map = 0x1A00 + i;
        add(od, map, 0, DataType.UNSIGNED8, AccessType.RW, u8(0), 0L, 8L);
        for (int s = 1; s <= 8; s++) {
            add(od, map, s, DataType.UNSIGNED32, AccessType.RW, u32(0), null, null);
        }
    }

    private static int defaultRpdoCobId(int i, int nodeId) {
        int[] base = {0x200, 0x300, 0x400, 0x500};
        return base[i] + (nodeId & 0x7F);
    }

    private static int defaultTpdoCobId(int i, int nodeId) {
        int[] base = {0x180, 0x280, 0x380, 0x480};
        return base[i] + (nodeId & 0x7F);
    }

    private static void resetMapping(ObjectDictionary od, int mapIndex) {
        od.write(mapIndex, 0, u8(0));
        for (int s = 1; s <= 8; s++) {
            od.write(mapIndex, s, u32(0));
        }
    }

    private static void add(ObjectDictionary od, int index, int sub, DataType type, AccessType access,
                            byte[] data, Long min, Long max) {
        od.put(new OdEntry(index, sub, type, access, data, min, max));
    }

    private static byte[] u8(long v) {
        return new byte[]{(byte) v};
    }

    private static byte[] u16(long v) {
        return new byte[]{(byte) v, (byte) (v >>> 8)};
    }

    private static byte[] u32(long v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }
}
