package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.core.FaultInjection;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtCommand;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtMaster;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分段传输进行中并发 NMT 状态迁移（复位通信、停止）的收敛规则：
 * 节点最终状态与传输最终状态一致、无悬挂会话、恢复通信后 SDO 立即可用，
 * 相同交错序列与相同故障种子重复运行结果完全相同。
 */
class NmtTransferInterleaveTest {

    private static final int REQ = 0x601;
    private static final int RESP = 0x581;

    @Test
    void resetCommunicationDuringDownloadAbortsSessionAndChannelRecovers() {
        CanNetwork network = new CanNetwork(new VirtualClock(false), new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        NmtMaster master = new NmtMaster(network.getBus());
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);

        // 传输进行中：initiate + 首段已确认。
        network.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x21, 0x00, 0x21, 0x00, 20, 0, 0, 0}));
        network.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x00, 1, 2, 3, 4, 5, 6, 7}));
        assertEquals(1, node.getSdoServer().activeSessionCount());

        // 并发复位通信：会话必须立即释放，节点经 INIT 回到 PRE-OP。
        master.sendCommand(NmtCommand.RESET_COMMUNICATION, 1);
        assertEquals(NmtState.PRE_OPERATIONAL, node.getState());
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 旧会话的后续段必须被拒绝（无会话），不得静默写入。
        recorder.clear();
        network.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x11, 8, 9, 10, 11, 12, 13, 14}));
        List<CanFrame> responses = recorder.withId(RESP);
        assertEquals(1, responses.size());
        assertEquals(0x80, responses.get(0).getU8(0) & 0xFF);
        assertEquals(AbortCode.SDO_COMMAND_SPECIFIER_INVALID.getCode(),
                responses.get(0).getU8(4) | (responses.get(0).getU8(5) << 8)
                        | (responses.get(0).getU8(6) << 16) | (responses.get(0).getU8(7) << 24));

        // 恢复通信后 SDO 通道立即可用。
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        byte[] blob = new byte[20];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (0x40 + i);
        }
        client.download(1, 0x2100, 0, blob);
        assertArrayEquals(blob, client.upload(1, 0x2100, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        client.shutdown();
        network.shutdown();
    }

    @Test
    void stopDuringUploadReleasesSessionAndSdoWorksAfterResume() {
        CanNetwork network = new CanNetwork(new VirtualClock(false), new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        NmtMaster master = new NmtMaster(network.getBus());

        node.getObjectDictionary().write(0x2100, 0, new byte[30]);
        // 传输进行中：上传 initiate + 拉取一段。
        network.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x40, 0x00, 0x21, 0x00, 0, 0, 0, 0}));
        network.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x60, 0, 0, 0, 0, 0, 0, 0}));
        assertEquals(1, node.getSdoServer().activeSessionCount());

        // 并发停止：STOPPED 下 SDO 被抑制，会话必须立即释放（不得悬挂）。
        master.sendCommand(NmtCommand.STOP, 1);
        assertEquals(NmtState.STOPPED, node.getState());
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // STOPPED 下旧会话的后续段被整体忽略（无任何响应）。
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);
        network.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x70, 0, 0, 0, 0, 0, 0, 0}));
        assertEquals(0, recorder.withId(RESP).size());

        // 恢复通信后 SDO 立即可用且数据完整。
        master.sendCommand(NmtCommand.ENTER_PRE_OPERATIONAL, 1);
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        assertEquals(30, client.upload(1, 0x2100, 0).length);
        assertEquals(0, node.getSdoServer().activeSessionCount());

        client.shutdown();
        network.shutdown();
    }

    @Test
    void sameInterleaveSequenceWithSameFaultSeedIsReproducible() {
        List<String> runA = interleavedRun(314L);
        List<String> runB = interleavedRun(314L);
        assertEquals(runA, runB,
                "相同交错序列与相同故障种子必须产生完全相同的帧轨迹");
    }

    /** 固定脚本：传输中途交错 NMT 迁移与故障注入，返回完整帧轨迹。 */
    private List<String> interleavedRun(long seed) {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(seed);
        faults.setDuplicateRate(0.3);
        faults.setDelayRange(1L, 3L);
        CanNetwork network = new CanNetwork(new VirtualClock(false), faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        NmtMaster master = new NmtMaster(network.getBus());
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);

        // 脚本化交错：分段下载进行中复位通信，再发起新传输并停止/恢复。
        network.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x21, 0x00, 0x21, 0x00, 14, 0, 0, 0}));
        network.getBus().send(0, new CanFrame(REQ,
                new byte[]{0x00, 1, 2, 3, 4, 5, 6, 7}));
        master.sendCommand(NmtCommand.RESET_COMMUNICATION, 1);
        network.tick(5);

        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        byte[] blob = new byte[14];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (0x30 + i);
        }
        try {
            client.download(1, 0x2100, 0, blob);
        } catch (RuntimeException ignored) {
            // 故障注入下本次传输可能失败；轨迹一致性才是断言目标。
        }
        master.sendCommand(NmtCommand.STOP, 1);
        network.tick(5);
        master.sendCommand(NmtCommand.ENTER_PRE_OPERATIONAL, 1);
        network.tick(5000);

        List<String> trace = new ArrayList<>();
        trace.add("state=" + node.getState());
        trace.add("sessions=" + node.getSdoServer().activeSessionCount());
        for (CanFrame frame : recorder.all()) {
            StringBuilder line = new StringBuilder(Integer.toHexString(frame.getId()));
            for (int i = 0; i < frame.getDataLength(); i++) {
                line.append(String.format(" %02X", frame.getU8(i)));
            }
            trace.add(line.toString());
        }
        client.shutdown();
        network.shutdown();
        return trace;
    }
}
