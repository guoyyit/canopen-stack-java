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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 传输中途中断（丢帧、延迟、对端超时）后的双方会话释放与立即恢复。
 * 中断在传输进行中真实构造，而非一次完整传输之后断言。
 */
class SdoMidTransferInterruptTest {

    private static final int REQ = 0x601;
    private static final int RESP = 0x581;

    @Test
    void stalledDownloadSessionTimesOutOnServerAndRetrySucceeds() {
        CanNetwork network = new CanNetwork(new VirtualClock(false), new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);

        // 真实构造进行中的分段下载会话：initiate + 一个数据段后客户端“消失”。
        network.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x21, 0x00, 0x21, 0x00, 20, 0, 0, 0}));
        network.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x00, 1, 2, 3, 4, 5, 6, 7}));
        assertEquals(1, node.getSdoServer().activeSessionCount());

        // 推进虚拟时钟超过服务器会话超时：服务器必须中止并释放会话。
        network.tick(3001);
        assertEquals(0, node.getSdoServer().activeSessionCount(), "服务器不得悬挂会话");

        // 服务器应已发出携带超时中止码的中止帧。
        List<CanFrame> aborts = recorder.withId(RESP);
        CanFrame last = aborts.get(aborts.size() - 1);
        assertEquals(0x80, last.getU8(0) & 0xFF);
        assertEquals(AbortCode.SDO_TIMEOUT.getCode(),
                last.getU8(4) | (last.getU8(5) << 8) | (last.getU8(6) << 16)
                        | (last.getU8(7) << 24));

        // 立即重发同一传输必须成功，且不得携带上次残留数据。
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        byte[] fresh = new byte[20];
        for (int i = 0; i < fresh.length; i++) {
            fresh[i] = (byte) (0xA0 + i);
        }
        client.download(1, 0x2100, 0, fresh);
        assertArrayEquals(fresh, client.upload(1, 0x2100, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        client.shutdown();
        network.shutdown();
    }

    @Test
    void droppedResponseCausesClientTimeoutAndBothSidesRecover() {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(5L);
        CanNetwork network = new CanNetwork(new VirtualClock(false), faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        client.setTimeoutMillis(500L);

        // 全部帧被丢弃：客户端在传输中途超时，服务器会话可能残留。
        faults.setDropRate(1.0);
        byte[] blob = new byte[30];
        SdoException thrown = assertThrows(SdoException.class,
                () -> client.download(1, 0x2100, 0, blob));
        assertEquals(AbortCode.SDO_TIMEOUT, thrown.getAbortCode());

        // 恢复总线并推进时钟：服务器侧任何残留会话都必须被超时清理。
        faults.setDropRate(0.0);
        network.tick(5000);
        assertEquals(0, node.getSdoServer().activeSessionCount(), "双方最终都必须释放会话");

        // 失败后立即重发同一请求必须成功，且数据为本次新数据。
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i * 3);
        }
        client.download(1, 0x2100, 0, blob);
        assertArrayEquals(blob, client.upload(1, 0x2100, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        client.shutdown();
        network.shutdown();
    }

    @Test
    void delayedFramesLeadToTimeoutThenCleanRecovery() {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(8L);
        faults.setDelayRange(100L, 100L);
        CanNetwork network = new CanNetwork(new VirtualClock(false), faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        client.setTimeoutMillis(50L);

        // 所有帧延迟 100ms：同步总线下客户端立即超时，帧随后才被分发。
        byte[] blob = new byte[30];
        assertThrows(SdoException.class, () -> client.download(1, 0x2100, 0, blob));

        // 分发延迟帧（initiate、客户端中止等），再推进超过会话超时。
        network.tick(200);
        network.tick(5000);
        assertEquals(0, node.getSdoServer().activeSessionCount(), "延迟帧不得留下悬挂会话");

        // 关闭延迟后立即重传必须成功。
        faults.setDelayRange(0L, 0L);
        client.download(1, 0x2100, 0, blob);
        assertArrayEquals(blob, client.upload(1, 0x2100, 0));

        client.shutdown();
        network.shutdown();
    }

    @Test
    void uploadInterruptedMidwayLeavesNoStaleDataOnRetry() {
        CanNetwork network = new CanNetwork(new VirtualClock(false), new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);

        byte[] original = new byte[21];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i + 1);
        }
        node.getObjectDictionary().write(0x2100, 0, original);

        // 真实构造进行中的上传会话：initiate + 拉取一段后客户端消失。
        network.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x40, 0x00, 0x21, 0x00, 0, 0, 0, 0}));
        network.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x60, 0, 0, 0, 0, 0, 0, 0}));
        assertEquals(1, node.getSdoServer().activeSessionCount());

        // 服务器超时释放。
        network.tick(3001);
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 新上传必须从头返回完整数据，不得残留上次偏移。
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        assertArrayEquals(original, client.upload(1, 0x2100, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        client.shutdown();
        network.shutdown();
    }
}
