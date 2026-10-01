package com.github.guoyyit.canopenstackjava.canopen.od;

/**
 * 对象字典条目。数值以 Long 承载，变长数据以 byte[] 承载。
 */
public class OdEntry {

    private final int index;
    private final int subindex;
    private final String name;
    private final DataType dataType;
    private final Access access;
    private Object value;
    private final Long minValue;
    private final Long maxValue;

    public OdEntry(int index, int subindex, String name, DataType dataType, Access access,
                   Object initialValue) {
        this(index, subindex, name, dataType, access, initialValue, null, null);
    }

    public OdEntry(int index, int subindex, String name, DataType dataType, Access access,
                   Object initialValue, Long minValue, Long maxValue) {
        if ((index & 0xFFFF) != index) {
            throw new IllegalArgumentException("OD index out of 16-bit range: " + index);
        }
        if (subindex < 0 || subindex > 0xFF) {
            throw new IllegalArgumentException("OD subindex out of range: " + subindex);
        }
        this.index = index;
        this.subindex = subindex;
        this.name = name;
        this.dataType = dataType;
        this.access = access;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.value = normalize(initialValue);
    }

    public int getIndex() {
        return index;
    }

    public int getSubindex() {
        return subindex;
    }

    public String getName() {
        return name;
    }

    public DataType getDataType() {
        return dataType;
    }

    public Access getAccess() {
        return access;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            long longValue = value instanceof Boolean b ? (b ? 1L : 0L)
                    : ((Number) value).longValue();
            if (minValue != null && longValue < minValue) {
                throw new OdException(AbortCode.VALUE_TOO_LOW,
                        "value " + longValue + " < min " + minValue + " at " + keyText());
            }
            if (maxValue != null && longValue > maxValue) {
                throw new OdException(AbortCode.VALUE_TOO_HIGH,
                        "value " + longValue + " > max " + maxValue + " at " + keyText());
            }
            if (!fitsDataType(longValue)) {
                throw new OdException(AbortCode.VALUE_RANGE_OVERFLOW,
                        "value " + longValue + " does not fit " + dataType);
            }
        }
        Object normalized = normalize(value);
        this.value = normalized;
    }

    public Long getMinValue() {
        return minValue;
    }

    public Long getMaxValue() {
        return maxValue;
    }

    private Object normalize(Object raw) {
        if (raw == null) {
            return dataType.getBitLength() == 0 ? new byte[0] : 0L;
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            long longValue = raw instanceof Boolean b ? (b ? 1L : 0L)
                    : ((Number) raw).longValue();
            if (!fitsDataType(longValue)) {
                throw new OdException(AbortCode.VALUE_RANGE_OVERFLOW,
                        "value " + longValue + " does not fit " + dataType);
            }
            return longValue;
        }
        if (raw instanceof byte[] bytes) {
            if (dataType != DataType.DOMAIN && dataType != DataType.VISIBLE_STRING) {
                throw new OdException(AbortCode.PARAMETER_INCOMPATIBILITY,
                        "byte[] only valid for DOMAIN/VISIBLE_STRING at " + keyText());
            }
            return bytes.clone();
        }
        if (raw instanceof String text && dataType == DataType.VISIBLE_STRING) {
            return text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        }
        throw new OdException(AbortCode.PARAMETER_INCOMPATIBILITY,
                "incompatible value type for " + dataType + " at " + keyText());
    }

    private boolean fitsDataType(long longValue) {
        if (dataType.getBitLength() == 0 || dataType == DataType.BOOLEAN) {
            return true;
        }
        int bits = dataType.getBitLength();
        if (dataType.isSigned()) {
            long min = bits == 64 ? Long.MIN_VALUE : -(1L << (bits - 1));
            long max = bits == 64 ? Long.MAX_VALUE : (1L << (bits - 1)) - 1L;
            return longValue >= min && longValue <= max;
        }
        long max = bits == 64 ? -1L : (1L << bits) - 1L;
        return bits == 64 || (longValue >= 0 && longValue <= max);
    }

    private String keyText() {
        return String.format("0x%04X:%02X", index, subindex);
    }
}
