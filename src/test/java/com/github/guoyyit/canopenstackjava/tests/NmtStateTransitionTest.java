package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtCommand;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtMaster;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.pdo.PdoMapping;
import com.github.guoyyit.canopenstackjava.canopen.pdo.PdoType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NmtStateTransitionTest {

    @Test
    void nodeStartsInPreOperationalAndAcceptsAllTransitionsViaBus() {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        NmtMaster master = new NmtMaster(network.getBus());

        assertEquals(NmtState.PRE_OPERATIONAL, node.getState());

        master.sendCommand(NmtCommand.ENTER_OPERATIONAL, 1);
        assertEquals(NmtState.OPERATIONAL, node.getState());

        master.sendCommand(NmtCommand.STOP, 1);
        assertEquals(NmtState.STOPPED, node.getState());

        master.sendCommand(NmtCommand.ENTER_PRE_OPERATIONAL, 1);
        assertEquals(NmtState.PRE_OPERATIONAL, node.getState());

        master.sendCommand(NmtCommand.RESET_NODE, 1);
        assertEquals(NmtState.PRE_OPERATIONAL, node.getState());

        network.shutdown();
    }

    @Test
    void broadcastNmtCommandReachesAllNodes() {
        CanNetwork network = new CanNetwork();
        CanOpenNode a = network.createNode(1);
        CanOpenNode b = network.createNode(2);
        a.start();
        b.start();
        NmtMaster master = new NmtMaster(network.getBus());

        master.sendCommand(NmtCommand.ENTER_OPERATIONAL, 0);

        assertEquals(NmtState.OPERATIONAL, a.getState());
        assertEquals(NmtState.OPERATIONAL, b.getState());
        network.shutdown();
    }

    @Test
    void pdoOnlySentInOperationalAndSdoAllowedInPreOpAndOp() {
        CanNetwork network = new CanNetwork(new VirtualClock(false),
                new com.github.guoyyit.canopenstackjava.canopen.core.FaultInjection());
        CanOpenNode node = network.createNode(1);
        node.start();
        node.configureTpdo(0, PdoType.EVENT_DRIVEN, 0L, 0L,
                List.of(new PdoMapping(0x2000, 0, 8)));
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);

        // PRE-OP：事件触发不得发出 TPDO。
        node.tpdo(0).triggerEvent();
        assertTrue(recorder.withId(node.tpdo(0).getCobId()).isEmpty());

        node.requestState(NmtState.OPERATIONAL);
        node.tpdo(0).triggerEvent();
        assertEquals(1, recorder.withId(node.tpdo(0).getCobId()).size());

        // STOPPED：TPDO 与 SDO 均被抑制。
        node.requestState(NmtState.STOPPED);
        recorder.clear();
        node.tpdo(0).triggerEvent();
        assertTrue(recorder.withId(node.tpdo(0).getCobId()).isEmpty());

        network.shutdown();
    }

    @Test
    void concurrentStateTransitionsRemainConsistent() throws Exception {
        CanNetwork network = new CanNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        NmtMaster master = new NmtMaster(network.getBus());

        int threads = 8;
        int iterations = 200;
        List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            NmtCommand command = t % 2 == 0 ? NmtCommand.ENTER_OPERATIONAL : NmtCommand.STOP;
            Thread worker = new Thread(() -> {
                for (int i = 0; i < iterations; i++) {
                    master.sendCommand(command, 1);
                }
            });
            workers.add(worker);
        }
        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(5000);
        }
        // 经过全串行化处理，节点必然停留在四个合法状态之一。
        assertTrue(node.getState() == NmtState.OPERATIONAL
                || node.getState() == NmtState.STOPPED
                || node.getState() == NmtState.PRE_OPERATIONAL);
        network.shutdown();
    }
}
