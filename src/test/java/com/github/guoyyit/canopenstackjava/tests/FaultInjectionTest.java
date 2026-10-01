package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.FaultInjection;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.pdo.PdoMapping;
import com.github.guoyyit.canopenstackjava.canopen.pdo.PdoType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultInjectionTest {

    private CanNetwork networkWith(FaultInjection faults) {
        return new CanNetwork(new VirtualClock(false), faults);
    }

    @Test
    void sameSeedProducesSameDropPattern() {
        List<Boolean> patternA = sampleDropPattern(12345L);
        List<Boolean> patternB = sampleDropPattern(12345L);
        List<Boolean> patternC = sampleDropPattern(999L);

        assertEquals(patternA, patternB);
        assertTrue(!patternA.equals(patternC) || patternA.stream().noneMatch(Boolean::booleanValue));
        assertTrue(patternA.stream().anyMatch(Boolean::booleanValue));
    }

    @Test
    void droppedFramesAreAbsentButNodesStayConsistent() {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(7L);
        faults.setDropRate(0.5);
        CanNetwork network = networkWith(faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        node.configureTpdo(0, PdoType.EVENT_DRIVEN, 0L, 0L,
                List.of(new PdoMapping(0x2000, 0, 8)));
        node.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.OPERATIONAL);
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);

        for (int i = 0; i < 40; i++) {
            node.tpdo(0).triggerEvent();
        }
        int received = recorder.withId(node.tpdo(0).getCobId()).size();
        assertTrue(received > 0 && received < 40, "drops should be probabilistic, got " + received);

        // 故障后节点状态仍合法，且总线仍可继续正常通信。
        assertEquals(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.OPERATIONAL,
                node.getState());
        node.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.STOPPED);
        assertEquals(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.STOPPED,
                node.getState());
        network.shutdown();
    }

    @Test
    void duplicatedFramesAreDeliveredTwiceAndDeterministic() {
        FaultInjection faultsA = new FaultInjection();
        faultsA.setSeed(11L);
        faultsA.setDuplicateRate(1.0);
        int countA = countTpdoDeliveries(faultsA);

        FaultInjection faultsB = new FaultInjection();
        faultsB.setSeed(11L);
        faultsB.setDuplicateRate(1.0);
        int countB = countTpdoDeliveries(faultsB);

        // 每次发送都被复制一次：记录数恰为触发数的两倍，且同种子可复现。
        assertEquals(20, countA);
        assertEquals(countA, countB);
    }

    @Test
    void delayedFramesArriveAfterVirtualTimeAdvances() {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(3L);
        faults.setDelayRange(10L, 10L);
        CanNetwork network = networkWith(faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        node.configureTpdo(0, PdoType.EVENT_DRIVEN, 0L, 0L,
                List.of(new PdoMapping(0x2000, 0, 8)));
        node.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.OPERATIONAL);
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);

        node.tpdo(0).triggerEvent();
        assertTrue(recorder.withId(node.tpdo(0).getCobId()).isEmpty(),
                "frame must be held back until virtual time advances");

        network.tick(9);
        assertTrue(recorder.withId(node.tpdo(0).getCobId()).isEmpty());
        network.tick(1);
        assertEquals(1, recorder.withId(node.tpdo(0).getCobId()).size());

        network.shutdown();
    }

    @Test
    void multiNodeFaultyTrafficNeverDeadlocks() {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(21L);
        faults.setDropRate(0.3);
        faults.setDuplicateRate(0.2);
        faults.setDelayRange(1L, 3L);
        CanNetwork network = networkWith(faults);
        List<CanOpenNode> nodes = new ArrayList<>();
        for (int id = 1; id <= 5; id++) {
            CanOpenNode node = network.createNode(id);
            node.start();
            node.configureTpdo(0, PdoType.EVENT_DRIVEN, 0L, 0L,
                    List.of(new PdoMapping(0x2000, 0, 8)));
            node.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.OPERATIONAL);
            nodes.add(node);
        }

        for (int round = 0; round < 30; round++) {
            for (CanOpenNode node : nodes) {
                node.tpdo(0).triggerEvent();
            }
            network.tick(2);
        }
        // 所有节点始终处于合法状态，网络推进顺畅（无死锁）。
        for (CanOpenNode node : nodes) {
            assertEquals(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.OPERATIONAL,
                    node.getState());
        }
        network.shutdown();
    }

    private List<Boolean> sampleDropPattern(long seed) {
        FaultInjection faults = new FaultInjection();
        faults.setSeed(seed);
        faults.setDropRate(0.5);
        List<Boolean> pattern = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            pattern.add(faults.shouldDrop());
        }
        return pattern;
    }

    private int countTpdoDeliveries(FaultInjection faults) {
        CanNetwork network = networkWith(faults);
        CanOpenNode node = network.createNode(1);
        node.start();
        node.configureTpdo(0, PdoType.EVENT_DRIVEN, 0L, 0L,
                List.of(new PdoMapping(0x2000, 0, 8)));
        node.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.OPERATIONAL);
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);
        for (int i = 0; i < 10; i++) {
            node.tpdo(0).triggerEvent();
        }
        int count = recorder.withId(node.tpdo(0).getCobId()).size();
        network.shutdown();
        return count;
    }
}
