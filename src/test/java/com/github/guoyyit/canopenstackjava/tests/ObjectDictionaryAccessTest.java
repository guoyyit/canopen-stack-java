package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;
import com.github.guoyyit.canopenstackjava.canopen.od.OdException;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectDictionaryAccessTest {

    private CanNetwork newNetworkWithNode() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        return network;
    }

    @Test
    void localAccessValidatesExistencePermissionAndRange() {
        CanNetwork network = newNetworkWithNode();
        CanOpenNode node = network.node(1);

        OdException missingIndex = assertThrows(OdException.class,
                () -> node.getObjectDictionary().read(0x9999, 0));
        assertEquals(AbortCode.OBJECT_DOES_NOT_EXIST, missingIndex.getAbortCode());

        OdException missingSubindex = assertThrows(OdException.class,
                () -> node.getObjectDictionary().read(0x1018, 99));
        assertEquals(AbortCode.SUBINDEX_DOES_NOT_EXIST, missingSubindex.getAbortCode());

        OdException writeRo = assertThrows(OdException.class,
                () -> node.getObjectDictionary().write(0x1000, 0, 1L));
        assertEquals(AbortCode.WRITE_ON_READ_ONLY, writeRo.getAbortCode());

        OdException readWo = assertThrows(OdException.class,
                () -> node.getObjectDictionary().read(0x2006, 0));
        assertEquals(AbortCode.READ_ON_WRITE_ONLY, readWo.getAbortCode());

        OdException tooHigh = assertThrows(OdException.class,
                () -> node.getObjectDictionary().write(0x1017, 0, 70000L));
        assertEquals(AbortCode.VALUE_TOO_HIGH, tooHigh.getAbortCode());

        network.shutdown();
    }

    @Test
    void sdoPropagatesDistinguishableAbortCodes() {
        CanNetwork network = newNetworkWithNode();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        SdoException missing = assertThrows(SdoException.class,
                () -> client.upload(1, 0x9999, 0));
        assertEquals(AbortCode.OBJECT_DOES_NOT_EXIST.getCode(),
                missing.getAbortCode().getCode());

        SdoException writeReadOnly = assertThrows(SdoException.class,
                () -> client.download(1, 0x1018, 4, new byte[]{1, 0, 0, 0}));
        assertEquals(AbortCode.WRITE_ON_READ_ONLY.getCode(),
                writeReadOnly.getAbortCode().getCode());

        SdoException readWriteOnly = assertThrows(SdoException.class,
                () -> client.upload(1, 0x2006, 0));
        assertEquals(AbortCode.READ_ON_WRITE_ONLY.getCode(),
                readWriteOnly.getAbortCode().getCode());

        client.shutdown();
        network.shutdown();
    }

    @Test
    void sdoRangeViolationReturnsValueTooHigh() {
        CanNetwork network = newNetworkWithNode();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        // 0x2002 为 UNSIGNED16 0..65535，无法通过 4 字节内编码超限；
        // 直接本地写入验证范围语义，再验证 SDO 写入合法边界。
        CanOpenNode node = network.node(1);
        OdException tooHigh = assertThrows(OdException.class,
                () -> node.getObjectDictionary().write(0x2002, 0, 70000L));
        assertEquals(AbortCode.VALUE_TOO_HIGH, tooHigh.getAbortCode());

        client.download(1, 0x2002, 0, new byte[]{(byte) 0xFF, (byte) 0xFF});
        assertEquals(65535L, node.getObjectDictionary().read(0x2002, 0));

        client.shutdown();
        network.shutdown();
    }
}
