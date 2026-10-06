package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.core.FaultInjection;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 分段传输 toggle 校验与重复段幂等处理（CiA 301）。
 */
class SdoToggleDuplicateTest {

    private static CanNetwork newNetwork(FaultInjection faults) {
        return new CanNetwork(new VirtualClock(false), faults);
    }

    private static int abortCodeOf(CanFrame frame) {
        byte[] data = frame.getData();
        return (data[4] & 0xFF) | ((data[5] & 0xFF) << 8)
                | ((data[6] & 0xFF) << 16) | ((data[7] & 0xFF) << 24);
    }

    private static byte[] blob(int length, int seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    void duplicateFramesOnBusKeepSegmentedTransfersConsistent() {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(99L);
        faults.setDuplicateRate(1.0);
        CanNetwork network = newNetwork(faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        // 每一帧都被复制：分段下载与上传仍必须返回完全一致的数据。
        byte[] blob = blob(42, 1);
        client.download(1, 0x2100, 0, blob);
        assertArrayEquals(blob, (byte[]) node.getObjectDictionary().read(0x2100, 0));
        assertArrayEquals(blob, client.upload(1, 0x2100, 0));

        // 整段对齐与单段情形同样正确。
        byte[] seven = blob(7, 2);
        client.download(1, 0x2101, 0, seven);
        assertArrayEquals(seven, client.upload(1, 0x2101, 0));

        byte[] five = blob(5, 3);
        client.download(1, 0x2100, 0, five);
        assertArrayEquals(five, client.upload(1, 0x2100, 0));

        // 不残留会话，后续传输立即成功。
        assertEquals(0, node.getSdoServer().activeSessionCount());
        client.download(1, 0x2000, 0, new byte[]{0x2A});
        assertEquals(0x2AL, node.getObjectDictionary().read(0x2000, 0));

        client.shutdown();
        network.shutdown();
    }

    @Test
    void duplicateInjectionIsDeterministicForSameSeed() {
        List<String> runA = recordDuplicateRun(77L);
        List<String> runB = recordDuplicateRun(77L);
        assertEquals(runA, runB, "same seed and same sequence must produce identical frames");
    }

    private static List<String> recordDuplicateRun(long seed) {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(seed);
        faults.setDuplicateRate(0.5);
        CanNetwork network = newNetwork(faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        client.download(1, 0x2100, 0, blob(30, 5));
        client.upload(1, 0x2100, 0);

        List<String> trace = new ArrayList<>();
        for (CanFrame frame : recorder.all()) {
            StringBuilder line = new StringBuilder(Integer.toHexString(frame.getId()));
            for (int i = 0; i < frame.getDataLength(); i++) {
                line.append(' ').append(frame.getU8(i));
            }
            trace.add(line.toString());
        }
        client.shutdown();
        network.shutdown();
        return trace;
    }

    @Test
    void outOfOrderUploadSegmentRequestIsRejectedWithToggleAbort() {
        CanNetwork network = newNetwork(new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        client.download(1, 0x2100, 0, blob(20, 6));

        RawSdoMaster master = RawSdoMaster.attach(network.getBus(), 90);
        master.initiateUpload(1, 0x2100, 0);
        assertEquals(0x41, master.lastResponse().getU8(0));

        // 首个上传段请求 toggle=1：真正的乱序，必须被拒绝并释放会话。
        master.clearResponses();
        master.uploadSegmentRequest(1, 1);
        CanFrame abort = master.lastResponse();
        assertNotNull(abort);
        assertEquals(0x80, abort.getU8(0));
        assertEquals(AbortCode.TOGGLE_BIT_INVALID.getCode(), abortCodeOf(abort));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 拒绝后新传输立即成功。
        assertArrayEquals(blob(20, 6), client.upload(1, 0x2100, 0));

        client.shutdown();
        network.shutdown();
    }

    @Test
    void outOfOrderDownloadSegmentIsRejectedWithToggleAbort() {
        CanNetwork network = newNetwork(new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();

        RawSdoMaster master = RawSdoMaster.attach(network.getBus(), 90);
        master.initiateDownload(1, 0x2100, 0, 20);
        assertEquals(0x60, master.lastResponse().getU8(0));

        // 首个下载段 toggle=1：乱序，拒绝并释放会话。
        master.clearResponses();
        master.downloadSegment(1, 1, blob(20, 7), 0, 7, false);
        CanFrame abort = master.lastResponse();
        assertNotNull(abort);
        assertEquals(0x80, abort.getU8(0));
        assertEquals(AbortCode.TOGGLE_BIT_INVALID.getCode(), abortCodeOf(abort));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 拒绝不污染对象字典，也不影响后续传输。
        assertEquals(0, ((byte[]) node.getObjectDictionary().read(0x2100, 0)).length);
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        byte[] data = blob(20, 8);
        client.download(1, 0x2100, 0, data);
        assertArrayEquals(data, client.upload(1, 0x2100, 0));

        client.shutdown();
        network.shutdown();
    }

    @Test
    void duplicateUploadSegmentRequestResendsIdenticalResponse() {
        CanNetwork network = newNetwork(new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        byte[] data = blob(20, 9);
        node.getObjectDictionary().write(0x2100, 0, data);

        RawSdoMaster master = RawSdoMaster.attach(network.getBus(), 90);
        master.initiateUpload(1, 0x2100, 0);
        master.clearResponses();

        // 同一请求连续发送两次：第二次必须返回与第一次完全相同的段，不得推进。
        master.uploadSegmentRequest(1, 0);
        master.uploadSegmentRequest(1, 0);
        List<CanFrame> responses = master.responses();
        assertEquals(2, responses.size());
        assertArrayEquals(responses.get(0).getData(), responses.get(1).getData());
        assertEquals(0, (responses.get(0).getU8(0) >>> 4) & 0x01, "segment toggle 0");

        // 继续正常完成传输，重组数据必须与对象字典一致。
        master.clearResponses();
        master.uploadSegmentRequest(1, 1);
        master.uploadSegmentRequest(1, 0);
        responses = master.responses();
        assertEquals(2, responses.size());
        byte[] reassembled = new byte[20];
        System.arraycopy(data, 0, reassembled, 0, 7);
        System.arraycopy(responses.get(0).getData(), 1, reassembled, 7, 7);
        System.arraycopy(responses.get(1).getData(), 1, reassembled, 14, 6);
        assertArrayEquals(data, reassembled);
        assertEquals(0, node.getSdoServer().activeSessionCount());

        network.shutdown();
    }

    @Test
    void duplicateFinalSegmentAfterCompletionGetsSameResult() {
        CanNetwork network = newNetwork(new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        byte[] data = blob(10, 10);
        node.getObjectDictionary().write(0x2100, 0, data);

        RawSdoMaster master = RawSdoMaster.attach(network.getBus(), 90);
        // 完成一次上传（10 字节 = 2 段），末段请求 toggle=1。
        master.initiateUpload(1, 0x2100, 0);
        master.uploadSegmentRequest(1, 0);
        master.clearResponses();
        master.uploadSegmentRequest(1, 1);
        CanFrame finalSegment = master.lastResponse();
        assertNotNull(finalSegment);
        assertEquals(1, finalSegment.getU8(0) & 0x01, "last segment flag");
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 传输已结束后重复末段请求：按相同结果回答，不得判为协议错误。
        master.clearResponses();
        master.uploadSegmentRequest(1, 1);
        assertArrayEquals(finalSegment.getData(), master.lastResponse().getData());

        // 完成一次下载后重复末段：重发相同应答，不重复累积数据。
        byte[] download = blob(10, 11);
        master.clearResponses();
        master.initiateDownload(1, 0x2100, 0, 10);
        master.downloadSegment(1, 0, download, 0, 7, false);
        master.clearResponses();
        master.downloadSegment(1, 1, download, 7, 3, true);
        CanFrame finalAck = master.lastResponse();
        assertNotNull(finalAck);
        assertEquals(0x30, finalAck.getU8(0) & 0xF0);
        assertArrayEquals(download, (byte[]) node.getObjectDictionary().read(0x2100, 0));

        master.clearResponses();
        master.downloadSegment(1, 1, download, 7, 3, true);
        assertArrayEquals(finalAck.getData(), master.lastResponse().getData());
        assertArrayEquals(download, (byte[]) node.getObjectDictionary().read(0x2100, 0));

        network.shutdown();
    }
}
