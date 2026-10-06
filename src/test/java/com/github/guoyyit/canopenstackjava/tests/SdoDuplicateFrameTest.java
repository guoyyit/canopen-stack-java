package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.core.FaultInjection;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 重复帧注入下的分段传输一致性（需求：重复段请求/应答必须幂等处理，
 * 不得跳段、不得重复累积，上传结果必须与写入完全一致）。
 */
class SdoDuplicateFrameTest {

    private static CanNetwork networkWithDuplicates(double rate, long seed) {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(seed);
        faults.setDuplicateRate(rate);
        return new CanNetwork(new VirtualClock(false), faults);
    }

    @Test
    void everyFrameDuplicatedSegmentedDownloadAndUploadStayConsistent() {
        CanNetwork network = networkWithDuplicates(1.0, 77L);
        CanOpenNode node = network.createNode(1);
        node.start();
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        // 覆盖：5 字节（单段不满）、7 字节（恰好整段）、14 字节（整段对齐）、
        // 100 字节（多段不对齐）、1024 字节（远大于单帧容量）。
        int[] lengths = {5, 7, 14, 100, 1024};
        for (int length : lengths) {
            byte[] blob = new byte[length];
            new Random(length).nextBytes(blob);

            client.download(1, 0x2100, 0, blob);
            assertArrayEquals(blob, (byte[]) node.getObjectDictionary().read(0x2100, 0),
                    "download corrupted with duplicate frames, length=" + length);

            byte[] readBack = client.upload(1, 0x2100, 0);
            assertArrayEquals(blob, readBack,
                    "upload corrupted with duplicate frames, length=" + length);
            assertEquals(0, node.getSdoServer().activeSessionCount(),
                    "session must be released after length=" + length);
        }

        client.shutdown();
        network.shutdown();
    }

    @Test
    void probabilisticDuplicatesAreDeterministicForSameSeed() {
        List<String> runA = scriptedDuplicateRun(2024L);
        List<String> runB = scriptedDuplicateRun(2024L);
        assertEquals(runA, runB, "same seed and same script must produce identical frame traces");
    }

    private List<String> scriptedDuplicateRun(long seed) {
        CanNetwork network = networkWithDuplicates(0.5, seed);
        CanOpenNode node = network.createNode(1);
        node.start();
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);
        SdoClient client = new SdoClient(network.getBus(), network.getClock());

        byte[] blob = new byte[40];
        new Random(9).nextBytes(blob);
        client.download(1, 0x2100, 0, blob);
        byte[] readBack = client.upload(1, 0x2100, 0);
        assertArrayEquals(blob, readBack);

        List<String> trace = new ArrayList<>();
        for (CanFrame frame : recorder.all()) {
            StringBuilder line = new StringBuilder();
            line.append(Integer.toHexString(frame.getId())).append('#');
            for (int i = 0; i < frame.getDataLength(); i++) {
                line.append(String.format("%02X", frame.getU8(i)));
            }
            trace.add(line.toString());
        }
        client.shutdown();
        network.shutdown();
        return trace;
    }
}
