package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.FaultInjection;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatTimeoutTest {

    @Test
    void consumerDetectsTimeoutWithinGuardWindowAndRecovers() {
        CanNetwork network = new CanNetwork(new VirtualClock(false), new FaultInjection());
        CanOpenNode producer = network.createNode(1);
        CanOpenNode consumer = network.createNode(2);
        producer.getObjectDictionary().write(0x1017, 0, 100L);
        producer.start();
        consumer.start();

        AtomicInteger timeouts = new AtomicInteger();
        consumer.monitorHeartbeat(1, 150L, (nodeId, overdue) -> timeouts.incrementAndGet());

        // 运行 120ms：心跳正常，无超时。
        for (int i = 0; i < 60; i++) {
            network.tick(2);
        }
        assertEquals(0, timeouts.get());

        // 让生产者离线（停止发心跳），消费者在窗口外检出超时，仅边沿触发一次。
        producer.getObjectDictionary().write(0x1017, 0, 0L);
        for (int i = 0; i < 100; i++) {
            network.tick(2);
        }
        assertEquals(1, timeouts.get());

        // 恢复心跳：先让消费者收到一帧心跳，超时标志清除；再次掉线只再报一次。
        producer.getObjectDictionary().write(0x1017, 0, 100L);
        network.tick(2);
        for (int i = 0; i < 30; i++) {
            network.tick(2);
        }
        assertEquals(1, timeouts.get());
        producer.getObjectDictionary().write(0x1017, 0, 0L);
        for (int i = 0; i < 100; i++) {
            network.tick(2);
        }
        assertEquals(2, timeouts.get());

        network.shutdown();
    }

    @Test
    void stateChangesAreAnnouncedImmediatelyViaHeartbeat() {
        CanNetwork network = new CanNetwork(new VirtualClock(false), new FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.getObjectDictionary().write(0x1017, 0, 10_000L);
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);
        node.start();
        recorder.clear();

        node.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.OPERATIONAL);
        long opHeartbeats = recorder.withId(0x701).stream()
                .filter(f -> (f.getU8(0) & 0xFF) == 0x05)
                .count();
        assertTrue(opHeartbeats >= 1, "state change must immediately emit a heartbeat");
        network.shutdown();
    }
}
