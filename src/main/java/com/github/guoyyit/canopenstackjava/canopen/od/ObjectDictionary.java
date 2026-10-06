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

    /**
     * 外部写入拦截器：在权限与存在性校验之后、数值校验与落值之前调用。
     * 实现可抛出 {@link OdException} 拒绝写入，或执行附带语义（如写 0 清空历史）。
     * 仅作用于 {@link #write(int, int, Object)} 外部写入路径；
     * {@link #writeInternal(int, int, Object)} 不触发拦截器。
     */
    public interface WriteInterceptor {

        void onWrite(int index, int subindex, Object value);
    }

    private final Map<OdKey, OdEntry> entries = new LinkedHashMap<>();
    private volatile WriteInterceptor writeInterceptor;

    public void setWriteInterceptor(WriteInterceptor interceptor) {
        this.writeInterceptor = interceptor;
    }

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
        WriteInterceptor interceptor = this.writeInterceptor;
        if (interceptor != null) {
            interceptor.onWrite(index, subindex, value);
        }
        entry.setValue(value);
    }

    /**
     * 协议栈内部写入路径：绕过访问权限与写拦截器，仍校验存在性、类型与数值范围。
     * 供错误寄存器/错误条数等只读项的自动状态同步使用，外部访问不得使用。
     */
    public void writeInternal(int index, int subindex, Object value) {
        OdEntry entry = entry(index, subindex);
        entry.setValue(value);
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
