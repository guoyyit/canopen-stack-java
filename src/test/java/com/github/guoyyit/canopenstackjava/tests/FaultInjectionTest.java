package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.can.CanFrame;
import com.github.guoyyit.canopenstackjava.can.FaultProfile;
import com.github.guoyyit.canopenstackjava.can.SimulatedBus;
import com.github.guoyyit.canopenstackjava.nmt.NmtCommand;
import com.github.guoyyit.canopenstackjava.network.CanOpenNetwork;
import com.github.guoyyit.canopenstackjava.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.sdo.SdoClient;
import com.github.guoyyit.canopenstackjava.sdo.SdoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultInjectionTest {

    private ScheduledExecutorService scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void sameSeedProducesSameDropPattern() throws Exception {
        boolean[] runA = runDropPattern(42L);
        boolean[] runB = runDropPattern(42L);
        boolean[] runC = runDropPattern(7L);

        assertEquals(java.util.Arrays.toString(runA), java.util.Arrays.toString(runB),
                "same seed must reproduce the exact fault pattern");
        assertFalse(java.util.Arrays.equals(runA, runC),
                "a different seed should generally produce another pattern");
    }

    private boolean[] runDropPattern(long seed) throws InterruptedException {
        ScheduledExecutorService exec = Executors.newScheduledThreadPool(1);
        try {
            SimulatedBus bus = new SimulatedBus(new FaultProfile(0.5, 0.0, 0L, seed), exec);
            CountDownLatch delivered = new CountDownLatch(100);
            bus.addListener(f -> delivered.countDown());
            boolean[] deliveredFlags = new boolean[100];
            for (int i = 0; i < 100; i++) {
                final int index = i;
                bus.addListener(f -> {
                    if (f.getSequence() == index + 1) {
                        deliveredFlags[index] = true;
                    }
                });
            }
            for (int i = 0; i < 100; i++) {
                bus.send(new CanFrame(0x100 + i, new byte[0], 0));
            }
            delivered.await(1, TimeUnit.SECONDS);
            return deliveredFlags;
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void duplicateAndDelayDeliverWithoutDeadlock() throws Exception {
        scheduler = Executors.newScheduledThreadPool(3);
        SimulatedBus bus = new SimulatedBus(new FaultProfile(0.0, 1.0, 40L, 1L), scheduler);
        List<Integer> cobIds = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(20);
        bus.addListener(frame -> {
            cobIds.add(frame.getCobId());
            latch.countDown();
        });

        for (int i = 0; i < 10; i++) {
            bus.send(new CanFrame(0x100, new byte[]{(byte) i}, 0));
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS), "all duplicated frames must be delivered");
        assertEquals(20, cobIds.size());
    }

    @Test
    void nodeTransfersStayConsistentUnderMildFaults() throws Exception {
        scheduler = Executors.newScheduledThreadPool(4);
        CanOpenNetwork network = new CanOpenNetwork(
                new FaultProfile(0.2, 0.2, 5L, 99L), scheduler);
        CanOpenNode node = network.createNode(5);
        network.createNode(1);
        SdoClient client = network.createSdoClient(1);
        network.broadcastNmt(NmtCommand.START.getCode(), 5);
        waitForState(node, NmtState.OPERATIONAL);

        int successes = 0;
        int timeouts = 0;
        for (int i = 0; i < 20; i++) {
            try {
                client.download(5, 0x2000, 0, new byte[]{(byte) i, 0});
                successes++;
            } catch (SdoException e) {
                timeouts++;
            }
        }
        assertTrue(successes > 0, "some transfers should succeed");
        assertTrue(timeouts > 0, "faults should cause some retriable failures");

        // 故障撤除后通信必须恢复，且节点状态一致（无死锁 / 状态错乱）
        network.setFaultProfile(FaultProfile.noFaults());
        client.download(5, 0x2000, 0, new byte[]{0x2A, 0x00});
        byte[] finalValue = client.upload(5, 0x2000, 0);
        assertEquals(0x2A, finalValue[0] & 0xFF);
        assertEquals(NmtState.OPERATIONAL, node.getState());
        network.shutdown();
    }

    private void waitForState(CanOpenNode node, NmtState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (node.getState() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, node.getState());
    }
}
