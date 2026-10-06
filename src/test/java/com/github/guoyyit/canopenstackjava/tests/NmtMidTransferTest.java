package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.core.FaultInjection;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtCommand;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtMaster;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分段传输进行中并发 NMT 状态迁移（停止/复位通信/复位节点）：
 * 最终结果确定，节点状态与传输状态一致，不死锁不悬挂，恢复后 SDO 立即可用。
 */
class NmtMidTransferTest {

    private static byte[] blob(int length, int seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    void stopDuringSegmentedUploadConvergesAndRecovers() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        NmtMaster master = new NmtMaster(network.getBus());
        byte[] data = blob(30, 1);
        node.getObjectDictionary().write(0x2100, 0, data);

        // 分段上传进行中：初始化与第一段已完成。
        RawSdoMaster raw = RawSdoMaster.attach(network.getBus(), 90);
        raw.initiateUpload(1, 0x2100, 0);
        raw.uploadSegmentRequest(1, 0);
        assertEquals(1, node.getSdoServer().activeSessionCount());

        // 并发停止命令：节点进入 STOPPED，传输会话必须同步释放。
        master.sendCommand(NmtCommand.STOP, 1);
        assertEquals(NmtState.STOPPED, node.getState());
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // STOPPED 下段请求被忽略（无响应、不死锁、不悬挂）。
        raw.clearResponses();
        raw.uploadSegmentRequest(1, 1);
        assertEquals(0, raw.responseCount());

        // 复位通信后 SDO 通道立即可用，且读出数据完整一致。
        master.sendCommand(NmtCommand.RESET_COMMUNICATION, 1);
        assertEquals(NmtState.PRE_OPERATIONAL, node.getState());
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        assertArrayEquals(data, client.upload(1, 0x2100, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        client.shutdown();
        network.shutdown();
    }

    @Test
    void resetCommunicationDuringSegmentedDownloadConverges() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        NmtMaster master = new NmtMaster(network.getBus());

        // 分段下载进行中：已确认初始化与第一段。
        RawSdoMaster raw = RawSdoMaster.attach(network.getBus(), 90);
        byte[] data = blob(30, 2);
        raw.initiateDownload(1, 0x2100, 0, data.length);
        raw.downloadSegment(1, 0, data, 0, 7, false);
        assertEquals(1, node.getSdoServer().activeSessionCount());

        // 并发复位通信：会话释放，节点回到 PRE-OPERATIONAL。
        master.sendCommand(NmtCommand.RESET_COMMUNICATION, 1);
        assertEquals(NmtState.PRE_OPERATIONAL, node.getState());
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 未完成的下载不得提交部分数据。
        assertEquals(0, ((byte[]) node.getObjectDictionary().read(0x2100, 0)).length);

        // SDO 立即可用：重新完整下载并读回一致数据。
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        client.download(1, 0x2100, 0, data);
        assertArrayEquals(data, client.upload(1, 0x2100, 0));

        client.shutdown();
        network.shutdown();
    }

    @Test
    void resetNodeDuringSegmentedUploadConverges() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        NmtMaster master = new NmtMaster(network.getBus());
        byte[] data = blob(30, 3);
        node.getObjectDictionary().write(0x2100, 0, data);

        RawSdoMaster raw = RawSdoMaster.attach(network.getBus(), 90);
        raw.initiateUpload(1, 0x2100, 0);
        raw.uploadSegmentRequest(1, 0);
        assertEquals(1, node.getSdoServer().activeSessionCount());

        master.sendCommand(NmtCommand.RESET_NODE, 1);
        assertEquals(NmtState.PRE_OPERATIONAL, node.getState());
        assertEquals(0, node.getSdoServer().activeSessionCount());

        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        assertArrayEquals(data, client.upload(1, 0x2100, 0));

        client.shutdown();
        network.shutdown();
    }

    @Test
    void sameInterleavingAndSeedProducesIdenticalResults() {
        List<String> runA = interleavedRun(42L);
        List<String> runB = interleavedRun(42L);
        assertEquals(runA, runB,
                "identical interleaving and fault seed must converge identically");
    }

    /** 固定种子下混合丢帧/重复/延迟，交错执行分段传输与 NMT 迁移，记录全部帧。 */
    private static List<String> interleavedRun(long seed) {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(seed);
        faults.setDropRate(0.1);
        faults.setDuplicateRate(0.2);
        faults.setDelayRange(1L, 3L);
        CanNetwork network = new CanNetwork(new VirtualClock(false), faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);
        NmtMaster master = new NmtMaster(network.getBus());
        RawSdoMaster raw = RawSdoMaster.attach(network.getBus(), 90);

        node.getObjectDictionary().write(0x2100, 0, blob(30, 7));
        List<String> trace = new ArrayList<>();
        raw.initiateUpload(1, 0x2100, 0);
        raw.uploadSegmentRequest(1, 0);
        master.sendCommand(NmtCommand.STOP, 1);
        network.tick(5);
        master.sendCommand(NmtCommand.RESET_COMMUNICATION, 1);
        network.tick(5);
        raw.initiateUpload(1, 0x2100, 0);
        network.tick(20);

        trace.add("state=" + node.getState());
        trace.add("sessions=" + node.getSdoServer().activeSessionCount());
        for (CanFrame frame : recorder.all()) {
            StringBuilder line = new StringBuilder(Integer.toHexString(frame.getId()));
            for (int i = 0; i < frame.getDataLength(); i++) {
                line.append(' ').append(frame.getU8(i));
            }
            trace.add(line.toString());
        }
        network.shutdown();
        return trace;
    }
}
