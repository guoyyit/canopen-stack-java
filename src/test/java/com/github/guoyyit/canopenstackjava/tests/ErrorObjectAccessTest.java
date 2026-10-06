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
 * 0x1001 对外只读；0x1003:0 仅允许写 0 清空历史；清空后三者一致归零；
 * 本地错误上报仍自动维护这些只读项。
 */
class ErrorObjectAccessTest {

    private CanNetwork network;
    private CanOpenNode node;
    private SdoClient client;

    private void setUp() {
        network = new CanNetwork();
        node = network.createNode(1);
        node.start();
        client = new SdoClient(network.getBus(), network.getClock());
    }

    private void tearDown() {
        client.shutdown();
        network.shutdown();
    }

    @Test
    void errorRegisterIsReadOnlyViaSdoAndLocalPath() {
        setUp();
        // SDO 写入 0x1001 必须以可区分的只读中止码拒绝。
        SdoException sdoEx = assertThrows(SdoException.class,
                () -> client.download(1, 0x1001, 0, new byte[]{0x00}));
        assertEquals(AbortCode.WRITE_ON_READ_ONLY, sdoEx.getAbortCode());

        // 本地对象写入路径同样不得修改只读项。
        OdException odEx = assertThrows(OdException.class,
                () -> node.getObjectDictionary().write(0x1001, 0, 0x7FL));
        assertEquals(AbortCode.WRITE_ON_READ_ONLY, odEx.getAbortCode());

        // 读出不受限制。
        assertEquals(0L, node.getObjectDictionary().read(0x1001, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());
        tearDown();
    }

    @Test
    void localErrorReportingStillUpdatesReadOnlyRegisterAndCount() {
        setUp();
        node.raiseEmergency(0x5001);
        node.raiseEmergency(0x5002);

        // 错误寄存器与条数自动反映最新状态（内部维护，不受只读限制影响）。
        assertEquals(1L, node.getObjectDictionary().read(0x1001, 0));
        assertEquals(2L, node.getObjectDictionary().read(0x1003, 0));
        // 0x1003 子项镜像最新记录：sub1 为最近一次（低位紧急码、高位错误寄存器）。
        assertEquals(0x5002L | (1L << 16), node.getObjectDictionary().read(0x1003, 1));
        assertEquals(0x5001L | (1L << 16), node.getObjectDictionary().read(0x1003, 2));

        // SDO 对外读出与本地一致。
        assertEquals(1, client.upload(1, 0x1001, 0)[0]);
        assertEquals(2, client.upload(1, 0x1003, 0)[0]);
        tearDown();
    }

    @Test
    void writingNonZeroToErrorCountIsRejectedWithDistinguishableCode() {
        setUp();
        node.raiseEmergency(0x6000);

        SdoException ex = assertThrows(SdoException.class,
                () -> client.download(1, 0x1003, 0, new byte[]{0x05}));
        assertEquals(AbortCode.VALUE_TOO_HIGH, ex.getAbortCode(),
                "写入非 0 必须以可区分原因拒绝");

        // 拒绝后历史与计数保持不变。
        assertEquals(1, node.getEmergencyHistory().size());
        assertEquals(1L, node.getObjectDictionary().read(0x1003, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());
        tearDown();
    }

    @Test
    void writingZeroToErrorCountClearsHistoryRegisterAndFields() {
        setUp();
        node.raiseEmergency(0x6000);
        node.raiseEmergency(0x6001);
        assertEquals(2, node.getEmergencyHistory().size());

        // 写 0 清空：必须通过 SDO 成功执行。
        client.download(1, 0x1003, 0, new byte[]{0x00});

        // 错误历史、对外读出条数与错误寄存器一致归零。
        assertEquals(0, node.getEmergencyHistory().size());
        assertEquals(0L, node.getObjectDictionary().read(0x1003, 0));
        assertEquals(0L, node.getObjectDictionary().read(0x1001, 0));
        assertEquals(0L, node.getObjectDictionary().read(0x1003, 1));
        assertEquals(0, client.upload(1, 0x1003, 0)[0]);
        assertEquals(0, client.upload(1, 0x1001, 0)[0]);

        // 清空后本地错误上报继续正常工作。
        node.raiseEmergency(0x6002);
        assertEquals(1L, node.getObjectDictionary().read(0x1003, 0));
        assertEquals(1L, node.getObjectDictionary().read(0x1001, 0));
        assertEquals(0x6002L | (1L << 16), node.getObjectDictionary().read(0x1003, 1));
        tearDown();
    }
}
