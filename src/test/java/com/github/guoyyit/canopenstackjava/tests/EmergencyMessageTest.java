package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.core.FaultInjection;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.emcy.EmergencyMessage;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmergencyMessageTest {

    @Test
    void emergencyIsSentInPreOpAndRetainedInHistory() {
        CanNetwork network = new CanNetwork(new VirtualClock(false), new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);

        node.getEmergencyProducer().raiseError(0x5001);

        List<CanFrame> emcy = recorder.withId(0x081);
        assertEquals(1, emcy.size());
        assertEquals(0x01, emcy.get(0).getU8(0));
        assertEquals(0x50, emcy.get(0).getU8(1));
        assertEquals(1, node.getEmergencyHistory().size());
        assertEquals(0x5001, node.getEmergencyHistory().latest().emergencyCode());
        assertEquals(0x01, node.getEmergencyProducer().getErrorRegister() & 0xFF);

        network.shutdown();
    }

    @Test
    void historyKeepsEveryErrorIncludingThoseSuppressedInStopped() {
        CanNetwork network = new CanNetwork(new VirtualClock(false), new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);

        node.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.STOPPED);
        node.getEmergencyProducer().raiseError(0x6000);
        node.getEmergencyProducer().raiseError(0x6001);

        // STOPPED 下 EMCY 帧被抑制……
        assertTrue(recorder.withId(0x081).isEmpty());
        // ……但历史完整保留且可查询。
        assertEquals(2, node.getEmergencyHistory().size());
        assertEquals(0x6001, node.getEmergencyHistory().latest().emergencyCode());

        // 回到 PRE-OP 后再次报错，帧恢复发送，历史持续累加。
        node.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.PRE_OPERATIONAL);
        node.getEmergencyProducer().raiseError(0x6002);
        assertEquals(1, recorder.withId(0x081).size());
        assertEquals(3, node.getEmergencyHistory().size());

        // 序号单调递增且携带时间戳。
        List<EmergencyMessage> all = node.getEmergencyHistory().all();
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1, all.get(i).sequence());
            assertNotNull(all.get(i));
        }
        network.shutdown();
    }

    @Test
    void concurrentErrorsNeverLoseHistory() throws Exception {
        CanNetwork network = new CanNetwork(new VirtualClock(false), new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();

        int threads = 8;
        int perThread = 100;
        List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int code = 0x7000 + t;
            workers.add(new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    node.raiseEmergency(code);
                }
            }));
        }
        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(5000);
        }
        assertEquals(threads * perThread, node.getEmergencyHistory().size());
        network.shutdown();
    }
}
