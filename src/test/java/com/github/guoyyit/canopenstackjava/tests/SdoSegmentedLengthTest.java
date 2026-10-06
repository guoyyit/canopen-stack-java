package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分段传输长度覆盖：5 字节、7 字节、恰好整段对齐（14/21）、
 * 远大于单帧容量（100/1024），以及传输间无残留会话、可立即发起新传输。
 */
class SdoSegmentedLengthTest {

    @Test
    void segmentedUploadAndDownloadAreConsistentAcrossLengths() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        int[] lengths = {5, 7, 8, 14, 21, 100, 1024};
        for (int length : lengths) {
            byte[] blob = new byte[length];
            new Random(length * 31L).nextBytes(blob);

            client.download(1, 0x2100, 0, blob);
            assertEquals(0, node.getSdoServer().activeSessionCount(),
                    "下载结束后不得残留会话, length=" + length);

            byte[] readBack = client.upload(1, 0x2100, 0);
            assertArrayEquals(blob, readBack, "length=" + length);
            assertEquals(0, node.getSdoServer().activeSessionCount(),
                    "上传结束后不得残留会话, length=" + length);

            // 紧随其后的不同对象传输必须立即成功。
            client.download(1, 0x2101, 0, blob);
            assertArrayEquals(blob, client.upload(1, 0x2101, 0));
            // 同一对象再次传输也必须立即成功。
            client.download(1, 0x2100, 0, new byte[]{1, 2, 3, 4, 5});
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, client.upload(1, 0x2100, 0));
        }

        client.shutdown();
        network.shutdown();
    }
}
