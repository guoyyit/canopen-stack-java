package com.github.guoyyit.canopenstackjava.canopen.node;

import com.github.guoyyit.canopenstackjava.canopen.od.Access;
import com.github.guoyyit.canopenstackjava.canopen.od.DataType;
import com.github.guoyyit.canopenstackjava.canopen.od.ObjectDictionary;
import com.github.guoyyit.canopenstackjava.canopen.od.OdEntry;

/**
 * 节点通信子集的默认对象字典（CiA 301 通信对象），并预置一组可用于 PDO 映射的应用数据。
 */
final class DefaultDictionary {

    static final int PDO_CHANNEL_COUNT = CanOpenNode.PDO_CHANNEL_COUNT;

    private DefaultDictionary() {
    }

    static void populate(CanOpenNode node) {
        ObjectDictionary od = node.getObjectDictionary();
        int nodeId = node.getNodeId();

        od.define(new OdEntry(0x1000, 0, "Device type",
                DataType.UNSIGNED32, Access.READ_ONLY, 0L));
        od.define(new OdEntry(0x1001, 0, "Error register",
                DataType.UNSIGNED8, Access.READ_ONLY, 0L));
        // 0x1003:0 对外只允许写入 0（清空错误历史），由对象字典写拦截器强制执行。
        od.define(new OdEntry(0x1003, 0, "Number of emergency errors",
                DataType.UNSIGNED8, Access.READ_WRITE, 0L, 0L, 254L));
        for (int i = 1; i <= 8; i++) {
            od.define(new OdEntry(0x1003, i, "Standard error field " + i,
                    DataType.UNSIGNED32, Access.READ_ONLY, 0L));
        }
        od.define(new OdEntry(0x1005, 0, "COB-ID SYNC",
                DataType.UNSIGNED32, Access.READ_WRITE, 0x80L));
        od.define(new OdEntry(0x1006, 0, "Communication cycle period (us)",
                DataType.UNSIGNED32, Access.READ_WRITE, 0L, 0L, 0xFFFFFFFFL));
        od.define(new OdEntry(0x1017, 0, "Producer heartbeat time (ms)",
                DataType.UNSIGNED16, Access.READ_WRITE,
                (long) node.getConfig().getHeartbeatPeriodMillis(), 0L, 65535L));
        od.define(new OdEntry(0x1018, 0, "Identity highest sub-index",
                DataType.UNSIGNED8, Access.READ_ONLY, 4L));
        od.define(new OdEntry(0x1018, 1, "Vendor ID",
                DataType.UNSIGNED32, Access.READ_ONLY, 0L));
        od.define(new OdEntry(0x1018, 2, "Product code",
                DataType.UNSIGNED32, Access.READ_ONLY, 0L));
        od.define(new OdEntry(0x1018, 3, "Revision number",
                DataType.UNSIGNED32, Access.READ_ONLY, 1L));
        od.define(new OdEntry(0x1018, 4, "Serial number",
                DataType.UNSIGNED32, Access.READ_ONLY, (long) nodeId));

        od.define(new OdEntry(0x1200, 0, "SDO server param highest sub-index",
                DataType.UNSIGNED8, Access.READ_ONLY, 2L));
        od.define(new OdEntry(0x1200, 1, "SDO server COB-ID client->server",
                DataType.UNSIGNED32, Access.READ_ONLY, (long) (0x600 + nodeId)));
        od.define(new OdEntry(0x1200, 2, "SDO server COB-ID server->client",
                DataType.UNSIGNED32, Access.READ_ONLY, (long) (0x580 + nodeId)));

        for (int i = 0; i < PDO_CHANNEL_COUNT; i++) {
            int rpdoComm = 0x1400 + i;
            int rpdoMap = 0x1600 + i;
            int tpdoComm = 0x1800 + i;
            int tpdoMap = 0x1A00 + i;

            od.define(new OdEntry(rpdoComm, 0, "RPDO comm highest sub-index",
                    DataType.UNSIGNED8, Access.READ_ONLY, 2L));
            od.define(new OdEntry(rpdoComm, 1, "RPDO COB-ID",
                    DataType.UNSIGNED32, Access.READ_WRITE,
                    (long) (0x200 + i * 0x100 + nodeId)));
            od.define(new OdEntry(rpdoComm, 2, "RPDO transmission type",
                    DataType.UNSIGNED8, Access.READ_WRITE, 0xFFL, 0L, 255L));
            od.define(new OdEntry(rpdoMap, 0, "RPDO number of mapped objects",
                    DataType.UNSIGNED8, Access.READ_WRITE, 0L, 0L, 8L));
            for (int sub = 1; sub <= 8; sub++) {
                od.define(new OdEntry(rpdoMap, sub, "RPDO map entry " + sub,
                        DataType.UNSIGNED32, Access.READ_WRITE, 0L));
            }

            od.define(new OdEntry(tpdoComm, 0, "TPDO comm highest sub-index",
                    DataType.UNSIGNED8, Access.READ_ONLY, 5L));
            od.define(new OdEntry(tpdoComm, 1, "TPDO COB-ID",
                    DataType.UNSIGNED32, Access.READ_WRITE,
                    (long) (0x180 + i * 0x100 + nodeId)));
            od.define(new OdEntry(tpdoComm, 2, "TPDO transmission type",
                    DataType.UNSIGNED8, Access.READ_WRITE, 0xFFL, 0L, 255L));
            od.define(new OdEntry(tpdoComm, 3, "TPDO inhibit time (100us)",
                    DataType.UNSIGNED16, Access.READ_WRITE, 0L, 0L, 65535L));
            od.define(new OdEntry(tpdoComm, 5, "TPDO event timer (ms)",
                    DataType.UNSIGNED16, Access.READ_WRITE, 0L, 0L, 65535L));
            od.define(new OdEntry(tpdoMap, 0, "TPDO number of mapped objects",
                    DataType.UNSIGNED8, Access.READ_WRITE, 0L, 0L, 8L));
            for (int sub = 1; sub <= 8; sub++) {
                od.define(new OdEntry(tpdoMap, sub, "TPDO map entry " + sub,
                        DataType.UNSIGNED32, Access.READ_WRITE, 0L));
            }
        }

        // 应用数据区：提供不同位宽的可读写条目，供 PDO 映射与 SDO 读写测试。
        od.define(new OdEntry(0x2000, 0, "Application unsigned8 counter",
                DataType.UNSIGNED8, Access.READ_WRITE, 0L, 0L, 255L));
        od.define(new OdEntry(0x2001, 0, "Application signed8 value",
                DataType.INTEGER8, Access.READ_WRITE, 0L, -128L, 127L));
        od.define(new OdEntry(0x2002, 0, "Application unsigned16 value",
                DataType.UNSIGNED16, Access.READ_WRITE, 0L, 0L, 65535L));
        od.define(new OdEntry(0x2003, 0, "Application signed16 value",
                DataType.INTEGER16, Access.READ_WRITE, 0L, -32768L, 32767L));
        od.define(new OdEntry(0x2004, 0, "Application unsigned32 value",
                DataType.UNSIGNED32, Access.READ_WRITE, 0L));
        od.define(new OdEntry(0x2005, 0, "Application signed32 value",
                DataType.INTEGER32, Access.READ_WRITE, 0L));
        od.define(new OdEntry(0x2006, 0, "Application write-only command",
                DataType.UNSIGNED16, Access.WRITE_ONLY, 0L, 0L, 65535L));
        od.define(new OdEntry(0x2010, 1, "Application record byte A",
                DataType.UNSIGNED8, Access.READ_WRITE, 0L, 0L, 255L));
        od.define(new OdEntry(0x2010, 2, "Application record byte B",
                DataType.UNSIGNED8, Access.READ_WRITE, 0L, 0L, 255L));
        od.define(new OdEntry(0x2010, 3, "Application record word C",
                DataType.UNSIGNED16, Access.READ_WRITE, 0L, 0L, 65535L));
        od.define(new OdEntry(0x2100, 0, "Application blob (segmented SDO)",
                DataType.DOMAIN, Access.READ_WRITE, new byte[0]));
        od.define(new OdEntry(0x2101, 0, "Application string (segmented SDO)",
                DataType.VISIBLE_STRING, Access.READ_WRITE, new byte[0]));
    }
}
