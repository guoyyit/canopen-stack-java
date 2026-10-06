package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 重启恢复：必须在确实存在进行中分段会话的情况下触发持久化与恢复。
 * 恢复后不得悬挂会话，SDO 通道立即可用，持久化数据与恢复后对外读出一致。
 */
class RestartDuringTransferTest {

    private static final int REQ = 0x601;

    @TempDir
    Path tempDir;

    @Test
    void restartWithInFlightDownloadSessionLeavesNoDanglingSession() {
        File file = tempDir.resolve("mid-download.json").toFile();

        CanNetwork before = new CanNetwork();
        CanOpenNode node = before.createNode(1);
        node.start();
        node.getObjectDictionary().write(0x2100, 0, new byte[]{9, 9, 9});
        node.getObjectDictionary().write(0x2000, 0, 0x42L);

        // 真实构造进行中的分段下载会话：initiate + 一个数据段（未完成的 20 字节传输）。
        before.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x21, 0x00, 0x21, 0x00, 20, 0, 0, 0}));
        before.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x00, 1, 2, 3, 4, 5, 6, 7}));
        assertEquals(1, node.getSdoServer().activeSessionCount(), "持久化时必须存在进行中会话");

        before.save(file);
        before.shutdown();

        CanNetwork after = CanNetwork.load(file);
        CanOpenNode restored = after.node(1);
        assertEquals(0, restored.getSdoServer().activeSessionCount(),
                "恢复后不得悬挂任何分段会话");

        // 未完成的传输不得污染对象字典：0x2100 仍是传输前的值。
        assertArrayEquals(new byte[]{9, 9, 9},
                (byte[]) restored.getObjectDictionary().read(0x2100, 0));

        // SDO 通道立即可用，持久化数据与恢复后对外读出一致。
        SdoClient client = new SdoClient(after.getBus(), after.getClock());
        assertArrayEquals(new byte[]{0x42}, client.upload(1, 0x2000, 0));
        assertArrayEquals(new byte[]{9, 9, 9}, client.upload(1, 0x2100, 0));

        // 恢复后可立即发起新的分段传输。
        byte[] blob = new byte[33];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (0x70 + i);
        }
        client.download(1, 0x2100, 0, blob);
        assertArrayEquals(blob, client.upload(1, 0x2100, 0));
        assertEquals(0, restored.getSdoServer().activeSessionCount());

        client.shutdown();
        after.shutdown();
    }

    @Test
    void restartWithInFlightUploadSessionLeavesNoDanglingSession() {
        File file = tempDir.resolve("mid-upload.json").toFile();

        CanNetwork before = new CanNetwork();
        CanOpenNode node = before.createNode(1);
        node.start();
        byte[] blob = new byte[40];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i + 1);
        }
        node.getObjectDictionary().write(0x2100, 0, blob);

        // 真实构造进行中的分段上传会话：initiate + 拉取首段。
        before.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x40, 0x00, 0x21, 0x00, 0, 0, 0, 0}));
        before.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x60, 0, 0, 0, 0, 0, 0, 0}));
        assertEquals(1, node.getSdoServer().activeSessionCount(), "持久化时必须存在进行中会话");

        before.save(file);
        before.shutdown();

        CanNetwork after = CanNetwork.load(file);
        CanOpenNode restored = after.node(1);
        assertEquals(0, restored.getSdoServer().activeSessionCount(),
                "恢复后不得悬挂任何分段会话");

        // 持久化数据与恢复后对外读出一致：完整 40 字节可立即读出。
        SdoClient client = new SdoClient(after.getBus(), after.getClock());
        assertArrayEquals(blob, client.upload(1, 0x2100, 0));
        assertEquals(0, restored.getSdoServer().activeSessionCount());

        client.shutdown();
        after.shutdown();
    }
}
