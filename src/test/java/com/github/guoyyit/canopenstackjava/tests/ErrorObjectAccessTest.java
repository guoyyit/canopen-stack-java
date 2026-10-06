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

/**
 * 错误寄存器（0x1001）与预定义错误字段（0x1003）的访问语义：
 * 只读项拒绝外部写入、条数项只允许写 0 清空、本地错误上报仍自动同步。
 */
class ErrorObjectAccessTest {

    @Test
    void errorRegisterRejectsExternalWritesWithReadOnlyCode() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        // SDO 路径：写入错误寄存器被只读错误码拒绝。
        SdoException sdoEx = assertThrows(SdoException.class,
                () -> client.download(1, 0x1001, 0, new byte[]{0x7F}));
        assertEquals(AbortCode.WRITE_ON_READ_ONLY, sdoEx.getAbortCode());
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 本地对象写入路径同样被拒绝，且错误码可区分。
        OdException odEx = assertThrows(OdException.class,
                () -> node.getObjectDictionary().write(0x1001, 0, 0x7FL));
        assertEquals(AbortCode.WRITE_ON_READ_ONLY, odEx.getAbortCode());

        // 值未被修改。
        assertEquals(0L, node.getObjectDictionary().read(0x1001, 0));

        client.shutdown();
        network.shutdown();
    }

    @Test
    void errorFieldCountAcceptsOnlyZeroAndClearsHistory() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        node.raiseEmergency(0x3001);
        node.raiseEmergency(0x3002);
        node.raiseEmergency(0x3003);
        assertEquals(3, node.getEmergencyHistory().size());
        assertEquals(1L, node.getObjectDictionary().read(0x1001, 0));
        assertEquals(3L, node.getObjectDictionary().read(0x1003, 0));

        // 写入非零值：以可区分原因拒绝，历史保持不变。
        SdoException ex = assertThrows(SdoException.class,
                () -> client.download(1, 0x1003, 0, new byte[]{5}));
        assertEquals(AbortCode.VALUE_RANGE_OVERFLOW, ex.getAbortCode());
        assertEquals(3, node.getEmergencyHistory().size());
        assertEquals(3L, node.getObjectDictionary().read(0x1003, 0));

        // 写入 0：清空历史，条数与错误寄存器一致归零。
        client.download(1, 0x1003, 0, new byte[]{0});
        assertEquals(0, node.getEmergencyHistory().size());
        assertEquals(0L, node.getObjectDictionary().read(0x1003, 0));
        assertEquals(0L, node.getObjectDictionary().read(0x1001, 0));
        assertEquals(0, node.getEmergencyProducer().getErrorRegister());
        for (int sub = 1; sub <= 8; sub++) {
            assertEquals(0L, node.getObjectDictionary().read(0x1003, sub),
                    "error field sub " + sub + " must be cleared");
        }

        // 对外读出的条数也为 0。
        assertEquals(1, client.upload(1, 0x1003, 0).length);
        assertEquals(0, client.upload(1, 0x1003, 0)[0]);

        client.shutdown();
        network.shutdown();
    }

    @Test
    void localErrorReportingStillUpdatesReadOnlyObjects() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        // 本地错误上报自动更新只读的错误寄存器与条数。
        node.raiseEmergency(0x4001);
        assertEquals(1L, node.getObjectDictionary().read(0x1001, 0));
        assertEquals(1L, node.getObjectDictionary().read(0x1003, 0));
        // 错误字段子索引 1 携带最新记录（低 16 位为紧急码）。
        assertEquals(0x4001L | (1L << 16), node.getObjectDictionary().read(0x1003, 1));

        node.raiseEmergency(0x4002);
        assertEquals(2L, node.getObjectDictionary().read(0x1003, 0));
        assertEquals(0x4002L | (1L << 16), node.getObjectDictionary().read(0x1003, 1));
        assertEquals(0x4001L | (1L << 16), node.getObjectDictionary().read(0x1003, 2));

        // 经 SDO 对外读出的值与本地一致。
        assertEquals(1, client.upload(1, 0x1001, 0)[0]);
        assertEquals(2, client.upload(1, 0x1003, 0)[0]);

        // 清空后继续上报，状态仍自动反映最新值。
        client.download(1, 0x1003, 0, new byte[]{0});
        node.raiseEmergency(0x4003);
        assertEquals(1L, node.getObjectDictionary().read(0x1001, 0));
        assertEquals(1L, node.getObjectDictionary().read(0x1003, 0));
        assertEquals(1, node.getEmergencyHistory().size());

        client.shutdown();
        network.shutdown();
    }

    @Test
    void errorFieldSubEntriesAreReadOnly() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        node.raiseEmergency(0x5001);
        SdoException ex = assertThrows(SdoException.class,
                () -> client.download(1, 0x1003, 1, new byte[]{1, 2, 3, 4}));
        assertEquals(AbortCode.WRITE_ON_READ_ONLY, ex.getAbortCode());
        // 历史未被外部写入污染。
        assertEquals(1, node.getEmergencyHistory().size());
        assertEquals(0x5001L | (1L << 16), node.getObjectDictionary().read(0x1003, 1));

        client.shutdown();
        network.shutdown();
    }
}
