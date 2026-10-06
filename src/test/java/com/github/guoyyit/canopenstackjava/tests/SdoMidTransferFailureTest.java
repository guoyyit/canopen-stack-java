package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.core.FaultInjection;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoException;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 传输中途丢帧、延迟与对端超时后的双方会话释放与立即恢复。
 */
class SdoMidTransferFailureTest {

    private static byte[] blob(int length, int seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    void droppedSegmentMidDownloadReleasesBothSidesAndRetrySucceeds() {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(5L);
        CanNetwork network = new CanNetwork(new VirtualClock(false), faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        RawSdoMaster master = RawSdoMaster.attach(network.getBus(), 90);

        byte[] data = blob(20, 1);
        master.initiateDownload(1, 0x2100, 0, data.length);
        assertEquals(0x60, master.lastResponse().getU8(0));
        master.downloadSegment(1, 0, data, 0, 7, false);
        assertEquals(0x20, master.lastResponse().getU8(0) & 0xF0);
        assertEquals(1, node.getSdoServer().activeSessionCount());

        // 中途丢帧：段 1 与后续中止帧都被丢弃，服务器会话悬挂在原地。
        faults.setDropRate(1.0);
        master.clearResponses();
        master.downloadSegment(1, 1, data, 7, 7, false);
        assertEquals(0, master.responseCount(), "dropped segment produces no ack");
        master.abort(1, 0x2100, 0, AbortCode.SDO_TIMEOUT.getCode());
        assertEquals(1, node.getSdoServer().activeSessionCount(),
                "abort was dropped too, server session still stalled");

        // 对端超时：推进虚拟时钟超过会话超时窗口，服务器必须自行释放并通知。
        faults.setDropRate(0.0);
        master.clearResponses();
        network.tick(4000);
        assertEquals(0, node.getSdoServer().activeSessionCount());
        CanFrame timeoutAbort = master.lastResponse();
        assertEquals(0x80, timeoutAbort.getU8(0));
        assertEquals(AbortCode.SDO_TIMEOUT.getCode(), abortCodeOf(timeoutAbort));

        // 失败后立即重发同一请求必须成功，且不得返回残留数据。
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        client.download(1, 0x2100, 0, data);
        assertArrayEquals(data, (byte[]) node.getObjectDictionary().read(0x2100, 0));
        assertArrayEquals(data, client.upload(1, 0x2100, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        client.shutdown();
        network.shutdown();
    }

    @Test
    void clientTimeoutAbortsAndServerReleasesSession() {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(6L);
        CanNetwork network = new CanNetwork(new VirtualClock(false), faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        client.setTimeoutMillis(300L);

        // 全部帧丢失：客户端立即超时并向服务器发送中止（中止也被丢弃）。
        faults.setDropRate(1.0);
        assertThrows(SdoException.class, () -> client.download(1, 0x2100, 0, blob(20, 2)));

        // 恢复总线：服务器从未建立会话，客户端立即可用。
        faults.setDropRate(0.0);
        byte[] data = blob(20, 3);
        client.download(1, 0x2100, 0, data);
        assertArrayEquals(data, client.upload(1, 0x2100, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());
        assertTrue(recorder.all().size() > 0);

        client.shutdown();
        network.shutdown();
    }

    @Test
    void staleDelayedResponseIsNotReturnedByNextTransfer() {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(8L);
        faults.setDelayRange(50L, 50L);
        CanNetwork network = new CanNetwork(new VirtualClock(false), faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        byte[] first = blob(20, 4);
        node.getObjectDictionary().write(0x2100, 0, first);

        // 所有帧延迟 50ms：同步客户端等不到应答，立即超时并发送中止。
        assertThrows(SdoException.class, () -> client.upload(1, 0x2100, 0));

        // 推进时钟：延迟的初始化请求与中止帧送达，陈旧应答不得污染后续传输。
        faults.setDelayRange(0L, 0L);
        network.tick(200);
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 写入新数据后立即重传：必须返回新数据而不是上一次的陈旧应答。
        byte[] second = blob(20, 5);
        node.getObjectDictionary().write(0x2100, 0, second);
        assertArrayEquals(second, client.upload(1, 0x2100, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        client.shutdown();
        network.shutdown();
    }

    @Test
    void repeatedFailureAndRecoveryKeepsBothSidesConsistent() {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(12L);
        CanNetwork network = new CanNetwork(new VirtualClock(false), faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        for (int round = 0; round < 5; round++) {
            // 中途制造一次完全丢帧的失败传输。
            final byte[] lost = blob(30, round);
            faults.setDropRate(1.0);
            assertThrows(SdoException.class,
                    () -> client.download(1, 0x2100, 0, lost));
            faults.setDropRate(0.0);
            network.tick(4000);

            // 双方状态一致：无残留会话，新的传输立即成功且数据一致。
            assertEquals(0, node.getSdoServer().activeSessionCount());
            byte[] data = blob(30, 100 + round);
            client.download(1, 0x2100, 0, data);
            assertArrayEquals(data, client.upload(1, 0x2100, 0));
        }

        client.shutdown();
        network.shutdown();
    }

    private static int abortCodeOf(CanFrame frame) {
        byte[] data = frame.getData();
        return (data[4] & 0xFF) | ((data[5] & 0xFF) << 8)
                | ((data[6] & 0xFF) << 16) | ((data[7] & 0xFF) << 24);
    }
}
