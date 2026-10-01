package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.can.FaultProfile;
import com.github.guoyyit.canopenstackjava.network.CanOpenNetwork;
import com.github.guoyyit.canopenstackjava.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.nmt.NmtCommand;
import com.github.guoyyit.canopenstackjava.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.sdo.SdoClient;
import com.github.guoyyit.canopenstackjava.sdo.SdoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态迁移与在途数据传输并发时，结果必须确定：
 * 迁移与帧处理在节点独占线程上构成全序，
 * 复位先终止 SDO/PDO，因此不会出现会话悬挂或状态不一致。
 */
class ConcurrencyDeterminismTest {

    private ScheduledExecutorService scheduler;
    private CanOpenNetwork network;

    @AfterEach
    void tearDown() {
        if (network != null) {
            network.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void stopDuringRapidTransfersAlwaysEndsConsistent() throws Exception {
        scheduler = Executors.newScheduledThreadPool(4);
        for (int iteration = 0; iteration < 20; iteration++) {
            network = new CanOpenNetwork(FaultProfile.noFaults(), scheduler);
            CanOpenNode node = network.createNode(2);
            network.createNode(1);
            SdoClient client = network.createSdoClient(1, 150);
            network.broadcastNmt(NmtCommand.START.getCode(), 2);
            waitFor(node, NmtState.OPERATIONAL);

            Thread hammer = new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    try {
                        client.download(2, 0x2000, 0, new byte[]{(byte) i, (byte) (i >>> 8)});
                    } catch (SdoException | InterruptedException ignored) {
                        // 与 STOP/RESET 竞争时失败是允许的，但调用必须返回而非悬挂
                        return;
                    }
                }
            });
            hammer.start();
            network.broadcastNmt(NmtCommand.STOP.getCode(), 2);
            hammer.join(3000);

            assertEquals(NmtState.STOPPED, node.getState());
            assertTrue(!hammer.isAlive(), "a transfer racing with STOP must never hang");

            // STOPPED 下任何 SDO 都不可达
            boolean blocked = false;
            try {
                client.upload(2, 0x1018, 4);
            } catch (SdoException e) {
                blocked = true;
            }
            assertTrue(blocked, "SDO must be refused in STOPPED");

            // 恢复后通信重新可用
            network.broadcastNmt(NmtCommand.ENTER_PRE_OPERATIONAL.getCode(), 2);
            waitFor(node, NmtState.PRE_OPERATIONAL);
            byte[] value = client.upload(2, 0x2001, 0);
            assertEquals(2, value.length);

            network.shutdown();
            network = null;
        }
    }

    @Test
    void resetDuringSegmentedTransferTerminatesSession() throws Exception {
        scheduler = Executors.newScheduledThreadPool(4);
        network = new CanOpenNetwork(FaultProfile.noFaults(), scheduler);
        CanOpenNode node = network.createNode(7);
        network.createNode(1);
        SdoClient client = network.createSdoClient(1, 150);
        network.broadcastNmt(NmtCommand.START.getCode(), 7);
        waitFor(node, NmtState.OPERATIONAL);

        node.getObjectDictionary().put(new com.github.guoyyit.canopenstackjava.od.OdEntry(
                0x2700, 0, com.github.guoyyit.canopenstackjava.od.DataType.OCTET_STRING,
                com.github.guoyyit.canopenstackjava.od.AccessType.RW, new byte[0], null, null));

        // 发起分段下载的同时进行通信复位；无论谁先，系统都必须收敛
        Thread transfer = new Thread(() -> {
            try {
                client.download(7, 0x2700, 0, new byte[50]);
            } catch (SdoException | InterruptedException ignored) {
                // 与复位竞争时允许失败
            }
        });
        transfer.start();
        Thread.sleep(5);
        network.broadcastNmt(NmtCommand.RESET_COMMUNICATION.getCode(), 7);
        transfer.join(3000);
        assertTrue(!transfer.isAlive(), "segmented transfer must terminate, not dangle");

        waitFor(node, NmtState.PRE_OPERATIONAL);
        network.broadcastNmt(NmtCommand.START.getCode(), 7);
        waitFor(node, NmtState.OPERATIONAL);

        // 复位后通道干净：新的完整分段传输成功
        byte[] payload = new byte[50];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 3);
        }
        client.download(7, 0x2700, 0, payload);
        byte[] readBack = client.upload(7, 0x2700, 0);
        org.junit.jupiter.api.Assertions.assertArrayEquals(payload, readBack);
    }

    private void waitFor(CanOpenNode node, NmtState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (node.getState() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, node.getState());
    }
}
