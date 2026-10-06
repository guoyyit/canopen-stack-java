package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分段传输在多种长度下的数据一致性：5 字节、7 字节、整段对齐、
 * 远大于单帧容量以及 0 字节变长数据；每条链路结束后不得残留会话。
 */
class SdoSegmentedLengthTest {

    private static byte[] blob(int length, int seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    void segmentedRoundTripAcrossLengths() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        int[] lengths = {5, 6, 7, 8, 13, 14, 15, 21, 64, 100, 1024};
        for (int length : lengths) {
            byte[] data = blob(length, length);
            client.download(1, 0x2100, 0, data);
            assertEquals(0, node.getSdoServer().activeSessionCount(),
                    "no session may remain after download of " + length + " bytes");
            assertArrayEquals(data, (byte[]) node.getObjectDictionary().read(0x2100, 0),
                    "download of " + length + " bytes");
            assertArrayEquals(data, client.upload(1, 0x2100, 0),
                    "upload of " + length + " bytes");
            assertEquals(0, node.getSdoServer().activeSessionCount(),
                    "no session may remain after upload of " + length + " bytes");
        }

        client.shutdown();
        network.shutdown();
    }

    @Test
    void zeroLengthDomainRoundTrips() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        // 空 DOMAIN：下载 0 字节再读回 0 字节。
        client.download(1, 0x2100, 0, new byte[0]);
        assertEquals(0, ((byte[]) node.getObjectDictionary().read(0x2100, 0)).length);
        assertArrayEquals(new byte[0], client.upload(1, 0x2100, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 空 -> 非空 -> 空 的连续切换也必须正确。
        byte[] data = blob(9, 1);
        client.download(1, 0x2100, 0, data);
        assertArrayEquals(data, client.upload(1, 0x2100, 0));
        client.download(1, 0x2100, 0, new byte[0]);
        assertArrayEquals(new byte[0], client.upload(1, 0x2100, 0));

        client.shutdown();
        network.shutdown();
    }

    @Test
    void transfersAcrossDifferentObjectsDoNotInterfere() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        byte[] a = blob(30, 10);
        byte[] b = blob(17, 20);
        client.download(1, 0x2100, 0, a);
        client.download(1, 0x2101, 0, b);

        // 同一对象与不同对象交替发起的新传输都必须立即成功。
        assertArrayEquals(a, client.upload(1, 0x2100, 0));
        assertArrayEquals(b, client.upload(1, 0x2101, 0));
        assertArrayEquals(a, client.upload(1, 0x2100, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        client.shutdown();
        network.shutdown();
    }
}
