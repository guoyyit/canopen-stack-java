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

    @Test
    void restartWithInFlightDownloadSessionDropsPartialData(@TempDir Path tempDir) {
        File file = tempDir.resolve("inflight-download.json").toFile();

        CanNetwork before = new CanNetwork();
        CanOpenNode node = before.createNode(1);
        node.start();
        SdoClient client = new SdoClient(before.getBus(), before.getClock());

        // 已提交的基线数据。
        byte[] committed = new byte[20];
        for (int i = 0; i < committed.length; i++) {
            committed[i] = (byte) (0xA0 + i);
        }
        client.download(1, 0x2100, 0, committed);

        // 真实构造进行中的分段下载会话：初始化与两段已确认，传输未完成。
        RawSdoMaster raw = RawSdoMaster.attach(before.getBus(), 90);
        byte[] partial = new byte[30];
        for (int i = 0; i < partial.length; i++) {
            partial[i] = (byte) (0xF0 - i);
        }
        raw.initiateDownload(1, 0x2100, 0, partial.length);
        raw.downloadSegment(1, 0, partial, 0, 7, false);
        raw.downloadSegment(1, 1, partial, 7, 7, false);
        assertEquals(1, node.getSdoServer().activeSessionCount(),
                "an in-flight segmented session must exist before restart");

        before.save(file);
        before.shutdown();

        CanNetwork after = CanNetwork.load(file);
        CanOpenNode restored = after.node(1);
        assertEquals(0, restored.getSdoServer().activeSessionCount(),
                "no dangling session after restart");

        // 持久化数据与恢复后的对外读出一致：未完成的分段不得提交部分数据。
        SdoClient newClient = new SdoClient(after.getBus(), after.getClock());
        assertArrayEquals(committed, newClient.upload(1, 0x2100, 0));

        // SDO 通道立即可用：新的完整传输成功。
        newClient.download(1, 0x2100, 0, partial);
        assertArrayEquals(partial, newClient.upload(1, 0x2100, 0));

        newClient.shutdown();
        after.shutdown();
    }

    @Test
    void restartWithInFlightUploadSessionLeavesChannelUsable(@TempDir Path tempDir) {
        File file = tempDir.resolve("inflight-upload.json").toFile();

        CanNetwork before = new CanNetwork();
        CanOpenNode node = before.createNode(1);
        node.start();

        byte[] data = new byte[25];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 3);
        }
        node.getObjectDictionary().write(0x2100, 0, data);

        // 真实构造进行中的分段上传会话。
        RawSdoMaster raw = RawSdoMaster.attach(before.getBus(), 90);
        raw.initiateUpload(1, 0x2100, 0);
        raw.uploadSegmentRequest(1, 0);
        assertEquals(1, node.getSdoServer().activeSessionCount());

        before.save(file);
        before.shutdown();

        CanNetwork after = CanNetwork.load(file);
        CanOpenNode restored = after.node(1);
        assertEquals(0, restored.getSdoServer().activeSessionCount());

        // 恢复后 SDO 立即可用，读出与持久化数据一致。
        SdoClient newClient = new SdoClient(after.getBus(), after.getClock());
        assertArrayEquals(data, newClient.upload(1, 0x2100, 0));
        assertEquals(0, restored.getSdoServer().activeSessionCount());

        newClient.shutdown();
        after.shutdown();
    }
}
