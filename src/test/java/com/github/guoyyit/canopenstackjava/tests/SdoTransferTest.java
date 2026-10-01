package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.can.FaultProfile;
import com.github.guoyyit.canopenstackjava.network.CanOpenNetwork;
import com.github.guoyyit.canopenstackjava.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.nmt.NmtCommand;
import com.github.guoyyit.canopenstackjava.od.AccessType;
import com.github.guoyyit.canopenstackjava.od.DataType;
import com.github.guoyyit.canopenstackjava.od.OdEntry;
import com.github.guoyyit.canopenstackjava.sdo.SdoClient;
import com.github.guoyyit.canopenstackjava.sdo.SdoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SdoTransferTest {

    private ScheduledExecutorService scheduler;
    private CanOpenNetwork network;
    private CanOpenNode serverNode;
    private SdoClient client;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
        network = new CanOpenNetwork(FaultProfile.noFaults(), scheduler);
        serverNode = network.createNode(2);
        network.createNode(1);
        client = network.createSdoClient(1);
        network.broadcastNmt(NmtCommand.START.getCode(), 2);
    }

    @AfterEach
    void tearDown() {
        network.shutdown();
        scheduler.shutdownNow();
    }

    private void waitForState(CanOpenNode node, com.github.guoyyit.canopenstackjava.nmt.NmtState expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (node.getState() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, node.getState());
    }

    @Test
    void expeditedUploadAndDownload() throws Exception {
        waitForState(serverNode, com.github.guoyyit.canopenstackjava.nmt.NmtState.OPERATIONAL);

        byte[] name = client.upload(2, 0x1008, 0);
        assertEquals("CANopen Sim Node", new String(name, StandardCharsets.US_ASCII));

        byte[] value = new byte[]{(byte) 0x34, (byte) 0x12};
        client.download(2, 0x2000, 0, value);
        assertArrayEquals(value, serverNode.getObjectDictionary().read(0x2000, 0));
    }

    @Test
    void segmentedTransferHandlesLargeObjects() throws Exception {
        byte[] big = new byte[40];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 7 + 3);
        }
        serverNode.getObjectDictionary().put(new OdEntry(0x2500, 0,
                DataType.OCTET_STRING, AccessType.RW, new byte[0], null, null));

        client.download(2, 0x2500, 0, big);
        assertArrayEquals(big, serverNode.getObjectDictionary().read(0x2500, 0));

        byte[] readBack = client.upload(2, 0x2500, 0);
        assertArrayEquals(big, readBack);
    }

    @Test
    void abortCodesAreReturnedForIllegalAccess() throws Exception {
        waitForState(serverNode, com.github.guoyyit.canopenstackjava.nmt.NmtState.OPERATIONAL);

        SdoException missing = assertThrows(SdoException.class,
                () -> client.upload(2, 0x6000, 0));
        assertEquals(0x06020000, missing.getAbortCode());

        SdoException readOnly = assertThrows(SdoException.class,
                () -> client.download(2, 0x1001, 0, new byte[]{0}));
        assertEquals(0x06010002, readOnly.getAbortCode());

        // abort 后客户端与服务端会话均复位，可以立即发起新的成功传输
        byte[] value = new byte[]{1, 2};
        client.download(2, 0x2000, 0, value);
        assertArrayEquals(value, client.upload(2, 0x2000, 0));
    }

    @Test
    void sdoBlockedInStoppedStateAndResumesAfterRestart() throws Exception {
        waitForState(serverNode, com.github.guoyyit.canopenstackjava.nmt.NmtState.OPERATIONAL);
        network.broadcastNmt(NmtCommand.STOP.getCode(), 2);
        waitForState(serverNode, com.github.guoyyit.canopenstackjava.nmt.NmtState.STOPPED);

        SdoException timeout = assertThrows(SdoException.class,
                () -> client.upload(2, 0x1018, 4));
        assertEquals(0x05040000, timeout.getAbortCode());

        network.broadcastNmt(NmtCommand.ENTER_PRE_OPERATIONAL.getCode(), 2);
        waitForState(serverNode, com.github.guoyyit.canopenstackjava.nmt.NmtState.PRE_OPERATIONAL);
        byte[] value = client.upload(2, 0x1018, 4);
        assertEquals(4, value.length);
    }

    @Test
    void repeatedSegmentedRoundTripsRemainConsistent() throws Exception {
        waitForState(serverNode, com.github.guoyyit.canopenstackjava.nmt.NmtState.OPERATIONAL);
        serverNode.getObjectDictionary().put(new OdEntry(0x2501, 0,
                DataType.OCTET_STRING, AccessType.RW, new byte[0], null, null));

        for (int iteration = 0; iteration < 5; iteration++) {
            byte[] data = new byte[7 + iteration * 7];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i + iteration);
            }
            client.download(2, 0x2501, 0, data);
            assertArrayEquals(data, client.upload(2, 0x2501, 0));
        }

        // 多轮分段传输后，加速传输通道仍可立即复用，证明会话状态完全复位
        byte[] small = new byte[]{0x55, (byte) 0xAA};
        client.download(2, 0x2000, 0, small);
        assertArrayEquals(small, client.upload(2, 0x2000, 0));
    }
}
