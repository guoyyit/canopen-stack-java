package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.can.FaultProfile;
import com.github.guoyyit.canopenstackjava.hb.HeartbeatConsumer;
import com.github.guoyyit.canopenstackjava.network.CanOpenNetwork;
import com.github.guoyyit.canopenstackjava.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.nmt.NmtCommand;
import com.github.guoyyit.canopenstackjava.nmt.NmtState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatEmergencyTest {

    private ScheduledExecutorService scheduler;
    private CanOpenNetwork network;
    private HeartbeatConsumer consumer;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(3);
        network = new CanOpenNetwork(FaultProfile.noFaults(), scheduler);
        consumer = new HeartbeatConsumer(network.getBus(), scheduler);
        consumer.start();
    }

    @AfterEach
    void tearDown() {
        consumer.stop();
        network.shutdown();
        scheduler.shutdownNow();
    }

    @Test
    void heartbeatTimeoutDetectedWithinGuardWindow() throws Exception {
        AtomicInteger timeouts = new AtomicInteger();
        List<Integer> receivedStates = new CopyOnWriteArrayList<>();
        consumer.setListener(new HeartbeatConsumer.Listener() {
            @Override
            public void heartbeatTimedOut(int nodeId) {
                timeouts.incrementAndGet();
            }

            @Override
            public void heartbeatReceived(int nodeId, NmtState state) {
                if (nodeId == 2 && state != NmtState.PRE_OPERATIONAL) {
                    receivedStates.add(state.ordinal());
                }
            }
        });

        CanOpenNode node = network.createNode(2);
        node.getHeartbeatProducer().setPeriodMillis(50);
        consumer.monitor(2, 150);

        Thread.sleep(120);
        assertEquals(0, timeouts.get());

        node.getHeartbeatProducer().stop();
        long deadline = System.currentTimeMillis() + 1000;
        while (timeouts.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, timeouts.get(), "timeout reported exactly once");

        Thread.sleep(300);
        assertEquals(1, timeouts.get(), "no repeated timeout events");

        node.getHeartbeatProducer().start();
        network.broadcastNmt(NmtCommand.START.getCode(), 2);
        deadline = System.currentTimeMillis() + 1000;
        while (receivedStates.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(receivedStates.contains(NmtState.OPERATIONAL.ordinal()),
                "monitoring resumes after recovery");
    }

    @Test
    void emergencyFramesAreRecordedAndDeduplicated() {
        CanOpenNode node = network.createNode(3);
        node.getEmergencyService().raise(0x5000, new byte[]{1, 2, 3, 4, 5});
        node.getEmergencyService().raise(0x5000, new byte[]{1, 2, 3, 4, 5});
        node.getEmergencyService().raise(0x6100, null);

        var history = node.getEmergencyService().getHistory().getAll();
        assertEquals(3, history.size(), "history keeps every raise even if frame is deduplicated");
        assertEquals(0x5000, history.get(0).getErrorCode());
        assertEquals(0x6100, history.get(2).getErrorCode());
        assertEquals(0x01, history.get(0).getErrorRegister());
    }

    @Test
    void stateTransitionsAreObservableViaHeartbeat() throws Exception {
        CanOpenNode node = network.createNode(4);
        List<NmtState> states = new CopyOnWriteArrayList<>();
        consumer.setListener(new HeartbeatConsumer.Listener() {
            @Override
            public void heartbeatTimedOut(int nodeId) {
            }

            @Override
            public void heartbeatReceived(int nodeId, NmtState state) {
                if (nodeId == 4) {
                    states.add(state);
                }
            }
        });
        node.getHeartbeatProducer().setPeriodMillis(30);
        consumer.monitor(4, 500);
        network.broadcastNmt(NmtCommand.START.getCode(), 4);
        network.broadcastNmt(NmtCommand.STOP.getCode(), 4);

        long deadline = System.currentTimeMillis() + 1000;
        while ((!states.contains(NmtState.OPERATIONAL) || !states.contains(NmtState.STOPPED))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(states.contains(NmtState.OPERATIONAL));
        assertTrue(states.contains(NmtState.STOPPED));
    }
}
