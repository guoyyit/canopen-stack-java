package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.od.AbortCode;
import com.github.guoyyit.canopenstackjava.od.AccessType;
import com.github.guoyyit.canopenstackjava.od.DataType;
import com.github.guoyyit.canopenstackjava.od.OdAccessException;
import com.github.guoyyit.canopenstackjava.od.OdEntry;
import com.github.guoyyit.canopenstackjava.od.ObjectDictionary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectDictionaryTest {

    private ObjectDictionary dictionaryWithEntries() {
        ObjectDictionary od = new ObjectDictionary();
        od.put(new OdEntry(0x2000, 0, DataType.UNSIGNED16, AccessType.RW,
                new byte[]{0x00, 0x00}, 10L, 1000L));
        od.put(new OdEntry(0x2001, 0, DataType.UNSIGNED32, AccessType.RO,
                new byte[]{1, 2, 3, 4}, null, null));
        od.put(new OdEntry(0x2002, 1, DataType.UNSIGNED8, AccessType.WO,
                new byte[]{0}, 0L, 255L));
        return od;
    }

    @Test
    void readsAndWritesByIndexAndSubIndex() {
        ObjectDictionary od = dictionaryWithEntries();
        od.write(0x2000, 0, new byte[]{(byte) 0xF4, 0x01});
        assertArrayEquals(new byte[]{(byte) 0xF4, 0x01}, od.read(0x2000, 0));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, od.read(0x2001, 0));
    }

    @Test
    void distinguishesMissingIndexAndSubIndex() {
        ObjectDictionary od = dictionaryWithEntries();
        OdAccessException missing = assertThrows(OdAccessException.class,
                () -> od.read(0x2100, 0));
        assertEquals(AbortCode.OBJECT_DOES_NOT_EXIST.getCode(), missing.getAbortCode());

        OdAccessException badSub = assertThrows(OdAccessException.class,
                () -> od.read(0x2001, 9));
        assertEquals(AbortCode.SUBINDEX_DOES_NOT_EXIST.getCode(), badSub.getAbortCode());
    }

    @Test
    void enforcesAccessRights() {
        ObjectDictionary od = dictionaryWithEntries();
        OdAccessException readOnly = assertThrows(OdAccessException.class,
                () -> od.write(0x2001, 0, new byte[]{0, 0, 0, 0}));
        assertEquals(AbortCode.WRITE_READ_ONLY.getCode(), readOnly.getAbortCode());

        OdAccessException writeOnly = assertThrows(OdAccessException.class,
                () -> od.read(0x2002, 1));
        assertEquals(AbortCode.READ_WRITE_ONLY.getCode(), writeOnly.getAbortCode());
    }

    @Test
    void enforcesLengthAndRange() {
        ObjectDictionary od = dictionaryWithEntries();
        OdAccessException tooLong = assertThrows(OdAccessException.class,
                () -> od.write(0x2000, 0, new byte[]{1, 2, 3}));
        assertEquals(AbortCode.TYPE_MISMATCH_TOO_HIGH.getCode(), tooLong.getAbortCode());

        OdAccessException tooShort = assertThrows(OdAccessException.class,
                () -> od.write(0x2000, 0, new byte[]{1}));
        assertEquals(AbortCode.TYPE_MISMATCH_TOO_LOW.getCode(), tooShort.getAbortCode());

        OdAccessException belowMin = assertThrows(OdAccessException.class,
                () -> od.write(0x2000, 0, new byte[]{9, 0}));
        assertEquals(AbortCode.VALUE_RANGE_EXCEEDED.getCode(), belowMin.getAbortCode());

        OdAccessException aboveMax = assertThrows(OdAccessException.class,
                () -> od.write(0x2000, 0, new byte[]{(byte) 0xE9, 0x03}));
        assertEquals(AbortCode.VALUE_RANGE_EXCEEDED.getCode(), aboveMax.getAbortCode());

        od.write(0x2000, 0, new byte[]{(byte) 0xE8, 0x03});
    }

    @Test
    void boundaryValuesPassRangeCheck() {
        ObjectDictionary od = new ObjectDictionary();
        od.put(new OdEntry(0x3000, 0, DataType.UNSIGNED8, AccessType.RW,
                new byte[]{5}, 5L, 200L));
        od.write(0x3000, 0, new byte[]{5});
        od.write(0x3000, 0, new byte[]{(byte) 200});
        assertArrayEquals(new byte[]{(byte) 200}, od.read(0x3000, 0));
    }
}
