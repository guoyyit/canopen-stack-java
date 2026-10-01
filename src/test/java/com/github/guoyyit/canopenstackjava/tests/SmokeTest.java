package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeTest {

    @Test
    void expeditedSdoRoundTrip() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        client.download(1, 0x2000, 0, new byte[]{0x2A});
        byte[] readBack = client.upload(1, 0x2000, 0);
        assertEquals(1, readBack.length);
        assertEquals(0x2A, readBack[0] & 0xFF);

        client.shutdown();
        network.shutdown();
    }
}
