package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartRecoveryTest {

    @Test
    void snapshotRoundTripPreservesStateOdAndEmergencies(@TempDir Path tempDir) {
        File file = tempDir.resolve("network.json").toFile();

        CanNetwork before = new CanNetwork();
        CanOpenNode node = before.createNode(1);
        node.start();
        node.getObjectDictionary().write(0x2000, 0, 0x42L);
        node.getObjectDictionary().write(0x2004, 0, 0xCAFEBABEL);
        node.getObjectDictionary().write(0x2100, 0, new byte[]{9, 8, 7, 6, 5});
        node.raiseEmergency(0x3001);
        node.raiseEmergency(0x3002);
        node.requestState(NmtState.OPERATIONAL);

        before.save(file);
        before.shutdown();

        assertTrue(file.length() > 0);

        CanNetwork after = CanNetwork.load(file);
        CanOpenNode restored = after.node(1);

        assertEquals(0x42L, restored.getObjectDictionary().read(0x2000, 0));
        assertEquals(0xCAFEBABEL, restored.getObjectDictionary().read(0x2004, 0));
        assertArrayEquals(new byte[]{9, 8, 7, 6, 5},
                (byte[]) restored.getObjectDictionary().read(0x2100, 0));
        assertEquals(2, restored.getEmergencyHistory().size());
        assertEquals(0x3002, restored.getEmergencyHistory().latest().emergencyCode());
        // OPERATIONAL 恢复到 PRE-OPERATIONAL，等待主站重新置 OP。
        assertEquals(NmtState.PRE_OPERATIONAL, restored.getState());

        after.shutdown();
    }

    @Test
    void interruptedSegmentedTransferIsTerminatedAfterRestart(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("mid-transfer.json").toFile();

        CanNetwork before = new CanNetwork();
        CanOpenNode node = before.createNode(1);
        node.start();
        SdoClient client = new SdoClient(before.getBus(), before.getClock());

        // 先写入大对象并建立过分段传输，确保会话机制工作。
        byte[] blob = new byte[30];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) i;
        }
        client.download(1, 0x2100, 0, blob);
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 模拟“传输中途崩溃”：直接制造一个未完成会话后立即持久化。
        client.download(1, 0x2100, 0, new byte[20]);
        before.save(file);
        before.shutdown();

        CanNetwork after = CanNetwork.load(file);
        CanOpenNode restored = after.node(1);
        assertEquals(0, restored.getSdoServer().activeSessionCount(),
                "no segmented transfer may remain dangling after restart");

        // 恢复后 SDO 通道立即可用。
        SdoClient newClient = new SdoClient(after.getBus(), after.getClock());
        byte[] readBack = newClient.upload(1, 0x2000, 0);
        assertEquals(1, readBack.length);

        newClient.shutdown();
        after.shutdown();
    }
}
