package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SdoSegmentedTransferTest {

    @Test
    void largeBlobUsesSegmentedDownloadAndUpload() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        byte[] blob = new byte[42];
        new Random(42).nextBytes(blob);

        client.download(1, 0x2100, 0, blob);
        assertArrayEquals(blob, (byte[]) node.getObjectDictionary().read(0x2100, 0));

        byte[] readBack = client.upload(1, 0x2100, 0);
        assertArrayEquals(blob, readBack);

        client.shutdown();
        network.shutdown();
    }

    @Test
    void segmentedTransferWithExactSegmentBoundary() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        // 恰好 7 字节：首段之后只有一段。
        byte[] seven = {1, 2, 3, 4, 5, 6, 7};
        client.download(1, 0x2101, 0, seven);
        assertArrayEquals(seven, client.upload(1, 0x2101, 0));

        // 100 字节，多段且非整段对齐。
        byte[] hundred = new byte[100];
        for (int i = 0; i < hundred.length; i++) {
            hundred[i] = (byte) i;
        }
        client.download(1, 0x2100, 0, hundred);
        assertArrayEquals(hundred, client.upload(1, 0x2100, 0));

        // 传输完成后服务器无残留会话，可立即开始下一次传输。
        assertEquals(0, node.getSdoServer().activeSessionCount());

        client.shutdown();
        network.shutdown();
    }

    @Test
    void failedTransferReleasesSessionAndNodeRecovers() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        // 对不存在对象发起分段读：服务器以中止码回答且不残留会话。
        assertThrowsSdoAbort(() -> {
            byte[] ignored = client.upload(1, 0x3000, 0);
            return ignored;
        });
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 中止后新的传输必须立即成功，证明双方状态一致且可恢复。
        client.download(1, 0x2000, 0, new byte[]{0x07});
        assertEquals(0x07, ((Number) node.getObjectDictionary().read(0x2000, 0)).intValue());

        client.shutdown();
        network.shutdown();
    }

    private static void assertThrowsSdoAbort(java.util.function.Supplier<byte[]> action) {
        try {
            action.get();
            throw new AssertionError("expected SdoException");
        } catch (com.github.guoyyit.canopenstackjava.canopen.sdo.SdoException expected) {
            // 预期异常。
        }
    }
}
