package com.github.guoyyit.canopenstackjava.od;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 节点的对象字典：按 (索引, 子索引) 寻址并校验访问。 */
public final class ObjectDictionary {

    private final Map<Long, OdEntry> entries = new HashMap<>();

    public static long key(int index, int subIndex) {
        return ((long) (index & 0xFFFF) << 8) | (subIndex & 0xFFL);
    }

    public synchronized void put(OdEntry entry) {
        entries.put(key(entry.getIndex(), entry.getSubIndex()), entry);
    }

    public synchronized boolean contains(int index, int subIndex) {
        return entries.containsKey(key(index, subIndex));
    }

    public synchronized OdEntry getEntry(int index, int subIndex) {
        return entries.get(key(index, subIndex));
    }

    public synchronized byte[] read(int index, int subIndex) {
        OdEntry entry = requireEntry(index, subIndex);
        if (!entry.getAccess().canRead()) {
            throw new OdAccessException(AbortCode.READ_WRITE_ONLY,
                    "0x" + hex(index) + "/" + subIndex + " is write-only");
        }
        return entry.getData();
    }

    public synchronized void write(int index, int subIndex, byte[] value) {
        OdEntry entry = requireEntry(index, subIndex);
        if (!entry.getAccess().canWrite()) {
            throw new OdAccessException(AbortCode.WRITE_READ_ONLY,
                    "0x" + hex(index) + "/" + subIndex + " is not writable");
        }
        validateValue(entry, value);
        entry.setDataRaw(value);
    }

    /**
     * 重启恢复专用：装载已持久化的权威数据，跳过访问与范围校验
     * （协议参数可包含 0xFF 等运行期保留值），但仍要求条目存在。
     */
    public synchronized void restoreWrite(int index, int subIndex, byte[] value) {
        OdEntry entry = entries.get(key(index, subIndex));
        if (entry != null) {
            entry.setDataRaw(value);
        }
    }

    public synchronized Collection<OdEntry> entries() {
        return List.copyOf(entries.values());
    }

    private OdEntry requireEntry(int index, int subIndex) {
        OdEntry entry = entries.get(key(index, subIndex));
        if (entry == null) {
            if (entries.containsKey(key(index, 0))) {
                throw new OdAccessException(AbortCode.SUBINDEX_DOES_NOT_EXIST,
                        "sub-index " + subIndex + " of 0x" + hex(index) + " does not exist");
            }
            throw new OdAccessException(AbortCode.OBJECT_DOES_NOT_EXIST,
                    "0x" + hex(index) + "/" + subIndex + " not in object dictionary");
        }
        return entry;
    }

    private void validateValue(OdEntry entry, byte[] value) {
        if (value == null) {
            throw new OdAccessException(AbortCode.TYPE_MISMATCH_LENGTH, "null value");
        }
        int expected = entry.expectedSize();
        if (expected > 0 && value.length != expected) {
            throw new OdAccessException(value.length > expected
                    ? AbortCode.TYPE_MISMATCH_TOO_HIGH
                    : AbortCode.TYPE_MISMATCH_TOO_LOW,
                    "expected " + expected + " bytes but got " + value.length);
        }
        if (entry.getMin() != null || entry.getMax() != null) {
            long numeric = interpretForRange(entry, value);
            if (entry.getMin() != null && numeric < entry.getMin()) {
                throw new OdAccessException(AbortCode.VALUE_RANGE_EXCEEDED,
                        numeric + " below minimum " + entry.getMin());
            }
            if (entry.getMax() != null && numeric > entry.getMax()) {
                throw new OdAccessException(AbortCode.VALUE_RANGE_EXCEEDED,
                        numeric + " above maximum " + entry.getMax());
            }
        }
    }

    private long interpretForRange(OdEntry entry, byte[] value) {
        switch (entry.getType()) {
            case INTEGER8:
            case INTEGER16:
            case INTEGER32:
            case INTEGER64:
                long signed = 0L;
                for (int i = value.length - 1; i >= 0; i--) {
                    signed = (signed << 8) | (value[i] & 0xFFL);
                }
                return signed;
            default:
                long unsigned = 0L;
                for (int i = value.length - 1; i >= 0; i--) {
                    unsigned = (unsigned << 8) | (value[i] & 0xFFL);
                }
                return unsigned;
        }
    }

    private static String hex(int value) {
        return String.format("%04X", value);
    }
}
