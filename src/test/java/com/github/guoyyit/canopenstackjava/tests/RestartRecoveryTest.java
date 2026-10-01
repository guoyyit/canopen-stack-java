package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.can.FaultProfile;
import com.github.guoyyit.canopenstackjava.network.CanOpenNetwork;
import com.github.guoyyit.canopenstackjava.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.nmt.NmtCommand;
import com.github.guoyyit.canopenstackjava.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.persist.NodeSnapshot;
import com.github.guoyyit.canopenstackjava.persist.PersistenceService;
import com.github.guoyyit.canopenstackjava.sdo.SdoClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartRecoveryTest {

    @Test
    void stateOdAndEmergencyHistorySurviveRestart(@TempDir Path tempDir) throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
        String file = tempDir.resolve("sim.json").toString();
        PersistenceService persistence = new PersistenceService(file);

        CanOpenNetwork network = new CanOpenNetwork(FaultProfile.noFaults(), scheduler);
        CanOpenNode node = network.createNode(2);
        network.createNode(1);
        SdoClient client = network.createSdoClient(1);
        network.broadcastNmt(NmtCommand.START.getCode(), 2);
        waitForState(node, NmtState.OPERATIONAL);

        client.download(2, 0x2000, 0, new byte[]{0x78, 0x01});
        node.getEmergencyService().raise(0x3000, new byte[]{9, 8, 7, 6, 5});

        Map<Integer, NodeSnapshot> snapshots = network.shutdownAndSnapshot();
        persistence.save(snapshots);
        assertTrue(java.nio.file.Files.exists(Path.of(file)));

        CanOpenNetwork rebuilt = new CanOpenNetwork(FaultProfile.noFaults(), scheduler);
        CanOpenNode restoredNode = rebuilt.createNode(2);
        restoredNode.restore(persistence.load().get(2));

        assertEquals(NmtState.OPERATIONAL, restoredNode.getState());
        assertArrayEquals(new byte[]{0x78, 0x01},
                restoredNode.getObjectDictionary().read(0x2000, 0));
        assertEquals(1, restoredNode.getEmergencyService().getHistory().getAll().size());
        assertEquals(0x3000,
                restoredNode.getEmergencyService().getHistory().getAll().get(0).getErrorCode());

        rebuilt.shutdown();
        scheduler.shutdownNow();
    }

    @Test
    void inFlightSegmentedTransferDoesNotSurviveRestart() throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
        CanOpenNetwork network = new CanOpenNetwork(FaultProfile.noFaults(), scheduler);
        CanOpenNode node = network.createNode(6);

        node.getObjectDictionary().put(new com.github.guoyyit.canopenstackjava.od.OdEntry(
                0x2600, 0, com.github.guoyyit.canopenstackjava.od.DataType.OCTET_STRING,
                com.github.guoyyit.canopenstackjava.od.AccessType.RW, new byte[0], null, null));
        network.createNode(1);
        SdoClient client = network.createSdoClient(1);
        network.broadcastNmt(NmtCommand.START.getCode(), 6);
        waitForState(node, NmtState.OPERATIONAL);

        byte[] big = new byte[30];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i + 1);
        }
        client.download(6, 0x2600, 0, big);

        NodeSnapshot snapshot = node.snapshot();
        node.restore(snapshot);

        // 恢复后新的传输必须干净成功，证明旧会话已终止、没有悬挂段
        SdoClient freshClient = network.createSdoClient(10);
        byte[] readBack = freshClient.upload(6, 0x2600, 0);
        assertArrayEquals(big, readBack);

        network.shutdown();
        scheduler.shutdownNow();
    }

    private void waitForState(CanOpenNode node, NmtState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (node.getState() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, node.getState());
    }
}
