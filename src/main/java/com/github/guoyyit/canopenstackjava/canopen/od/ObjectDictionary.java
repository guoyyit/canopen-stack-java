package com.github.guoyyit.canopenstackjava.canopen.od;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 对象字典：按索引/子索引寻址，访问时校验存在性、权限与数据范围。
 */
public class ObjectDictionary {

    private final Map<OdKey, OdEntry> entries = new LinkedHashMap<>();

    public void define(OdEntry entry) {
        OdKey key = new OdKey(entry.getIndex(), entry.getSubindex());
        if (entries.containsKey(key)) {
            throw new IllegalArgumentException("duplicate OD entry " + key);
        }
        entries.put(key, entry);
    }

    public boolean exists(int index, int subindex) {
        return entries.containsKey(new OdKey(index, subindex));
    }

    public OdEntry entry(int index, int subindex) {
        OdEntry entry = entries.get(new OdKey(index, subindex));
        if (entry == null) {
            throw new OdException(indexExists(index)
                    ? AbortCode.SUBINDEX_DOES_NOT_EXIST
                    : AbortCode.OBJECT_DOES_NOT_EXIST,
                    String.format("no OD entry 0x%04X:%02X", index, subindex));
        }
        return entry;
    }

    public Object read(int index, int subindex) {
        OdEntry entry = entry(index, subindex);
        if (!entry.getAccess().isReadable()) {
            throw new OdException(AbortCode.READ_ON_WRITE_ONLY,
                    String.format("entry 0x%04X:%02X is write-only", index, subindex));
        }
        return entry.getValue();
    }

    public void write(int index, int subindex, Object value) {
        OdEntry entry = entry(index, subindex);
        if (!entry.getAccess().isWritable()) {
            throw new OdException(AbortCode.WRITE_ON_READ_ONLY,
                    String.format("entry 0x%04X:%02X is %s", index, subindex,
                            entry.getAccess() == Access.CONSTANT ? "constant" : "read-only"));
        }
        entry.setValue(value);
    }

    /**
     * 协议栈内部写入路径：绕过读/写权限校验（仍保留类型与范围校验），
     * 供错误寄存器、错误条数等只读项由栈内逻辑自动维护时使用。
     * 外部访问（SDO/本地 API）不得使用此路径修改只读项。
     */
    public void writeInternal(int index, int subindex, Object value) {
        entry(index, subindex).setValue(value);
    }

    public Set<OdKey> keys() {
        TreeSet<OdKey> sorted = new TreeSet<>(java.util.Comparator.comparingInt(OdKey::index)
                .thenComparingInt(OdKey::subindex));
        sorted.addAll(entries.keySet());
        return Collections.unmodifiableSet(sorted);
    }

    public int size() {
        return entries.size();
    }

    private boolean indexExists(int index) {
        for (OdKey key : entries.keySet()) {
            if (key.index() == index) {
                return true;
            }
        }
        return false;
    }
}
