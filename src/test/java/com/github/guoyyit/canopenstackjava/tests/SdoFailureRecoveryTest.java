package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.FaultInjection;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SdoFailureRecoveryTest {

    @Test
    void serverTimesOutStalledSessionAndBothSidesRecover() {
        CanNetwork network = new CanNetwork(new VirtualClock(false), new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        client.setTimeoutMillis(500L);

        // 正常分段传输建立基线。
        client.download(1, 0x2100, 0, new byte[20]);

        // 会话在服务器上正常结束；推进时钟超过超时窗口不应产生伪会话中止。
        network.tick(5000);
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 超时后仍可立即发起新的传输。
        client.download(1, 0x2000, 0, new byte[]{0x11});
        assertEquals(0x11L, node.getObjectDictionary().read(0x2000, 0));

        client.shutdown();
        network.shutdown();
    }

    @Test
    void abortCodeOnWritingReadOnlyIsDistinguishable() {
        CanNetwork network = new CanNetwork(new VirtualClock(false), new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        SdoException ex = org.junit.jupiter.api.Assertions.assertThrows(SdoException.class,
                () -> client.download(1, 0x1018, 1, new byte[]{1, 2, 3, 4}));
        assertEquals(AbortCode.WRITE_ON_READ_ONLY, ex.getAbortCode());
        assertEquals(0, node.getSdoServer().activeSessionCount());

        client.shutdown();
        network.shutdown();
    }

    @Test
    void repeatedSegmentTransferSucceedsManyTimes() {
        CanNetwork network = new CanNetwork(new VirtualClock(false), new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        for (int round = 0; round < 10; round++) {
            byte[] blob = new byte[15 + round];
            for (int i = 0; i < blob.length; i++) {
                blob[i] = (byte) (round + i);
            }
            client.download(1, 0x2100, 0, blob);
            org.junit.jupiter.api.Assertions.assertArrayEquals(blob,
                    client.upload(1, 0x2100, 0));
            assertEquals(0, node.getSdoServer().activeSessionCount());
        }

        client.shutdown();
        network.shutdown();
    }
}
