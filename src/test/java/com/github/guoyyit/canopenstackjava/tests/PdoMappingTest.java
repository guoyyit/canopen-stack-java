package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.can.CanFrame;
import com.github.guoyyit.canopenstackjava.can.FaultProfile;
import com.github.guoyyit.canopenstackjava.can.FrameListener;
import com.github.guoyyit.canopenstackjava.network.CanOpenNetwork;
import com.github.guoyyit.canopenstackjava.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.nmt.NmtCommand;
import com.github.guoyyit.canopenstackjava.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.od.AbortCode;
import com.github.guoyyit.canopenstackjava.od.OdAccessException;
import com.github.guoyyit.canopenstackjava.pdo.MappingEntry;
import com.github.guoyyit.canopenstackjava.pdo.PdoTriggerType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdoMappingTest {

    private ScheduledExecutorService scheduler;
    private CanOpenNetwork network;
    private CanOpenNode producer;
    private CanOpenNode consumer;
    private List<CanFrame> captured;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(3);
        network = new CanOpenNetwork(FaultProfile.noFaults(), scheduler);
        producer = network.createNode(2);
        consumer = network.createNode(3);
        captured = new CopyOnWriteArrayList<>();
        network.getBus().addListener((FrameListener) frame -> {
            if (frame.getCobId() >= 0x180 && frame.getCobId() <= 0x5FF) {
                captured.add(frame);
            }
        });
    }

    @AfterEach
    void tearDown() {
        network.shutdown();
        scheduler.shutdownNow();
    }

    private void waitForState(CanOpenNode node, NmtState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (node.getState() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, node.getState());
    }

    @Test
    void rejectsInvalidMappings() {
        OdAccessException missing = assertThrows(OdAccessException.class,
                () -> producer.configurePdo(0, true,
                        List.of(new MappingEntry(0x6000, 0, 16)),
                        PdoTriggerType.EVENT, 0x182, 0, 0));
        assertEquals(AbortCode.OBJECT_DOES_NOT_EXIST.getCode(), missing.getAbortCode());

        OdAccessException tooLong = assertThrows(OdAccessException.class,
                () -> producer.configurePdo(0, true,
                        List.of(new MappingEntry(0x2002, 0, 32),
                                new MappingEntry(0x2001, 0, 16),
                                new MappingEntry(0x2000, 0, 16),
                                new MappingEntry(0x1000, 0, 32)),
                        PdoTriggerType.EVENT, 0x182, 0, 0));
        assertEquals(AbortCode.PDO_LENGTH_EXCEEDED.getCode(), tooLong.getAbortCode());
    }

    @Test
    void mappingOnlyChangeableInPreOperational() throws Exception {
        producer.configurePdo(0, true,
                List.of(new MappingEntry(0x2001, 0, 16)),
                PdoTriggerType.EVENT, 0x182, 0, 0);
        network.broadcastNmt(NmtCommand.START.getCode(), 2);
        waitForState(producer, NmtState.OPERATIONAL);

        OdAccessException error = assertThrows(OdAccessException.class,
                () -> producer.configurePdo(1, true,
                        List.of(new MappingEntry(0x2001, 0, 16)),
                        PdoTriggerType.EVENT, 0x282, 0, 0));
        assertEquals(AbortCode.UNSUPPORTED_ACCESS.getCode(), error.getAbortCode());
    }

    @Test
    void eventTpdoDeliversMappedData() throws Exception {
        producer.configurePdo(0, true,
                List.of(new MappingEntry(0x2001, 0, 16)),
                PdoTriggerType.EVENT, 0x1AA, 0, 0);
        consumer.configurePdo(0, false,
                List.of(new MappingEntry(0x2000, 0, 16)),
                PdoTriggerType.EVENT, 0x1AA, 0, 0);

        network.broadcastNmt(NmtCommand.START.getCode(), 0);
        waitForState(producer, NmtState.OPERATIONAL);
        waitForState(consumer, NmtState.OPERATIONAL);

        producer.getObjectDictionary().write(0x2001, 0, new byte[]{0x34, 0x12});
        producer.getPdoManager().triggerEvent(0);

        long deadline = System.currentTimeMillis() + 2000;
        byte[] received = null;
        while (received == null && System.currentTimeMillis() < deadline) {
            received = consumer.getObjectDictionary().read(0x2000, 0);
            if ((received[0] & 0xFF) == 0 && (received[1] & 0xFF) == 0) {
                Thread.sleep(5);
                received = null;
            }
        }
        assertArrayEquals(new byte[]{0x34, 0x12},
                consumer.getObjectDictionary().read(0x2000, 0));
    }

    @Test
    void inhibitTimeSuppressesRapidEvents() throws Exception {
        producer.configurePdo(0, true,
                List.of(new MappingEntry(0x2001, 0, 16)),
                PdoTriggerType.EVENT, 0x1AB, 100_000L, 0);
        network.broadcastNmt(NmtCommand.START.getCode(), 2);
        waitForState(producer, NmtState.OPERATIONAL);

        captured.clear();
        producer.getObjectDictionary().write(0x2001, 0, new byte[]{1, 0});
        producer.getPdoManager().triggerEvent(0);
        producer.getObjectDictionary().write(0x2001, 0, new byte[]{2, 0});
        producer.getPdoManager().triggerEvent(0);
        Thread.sleep(150);

        long count = captured.stream().filter(f -> f.getCobId() == 0x1AB).count();
        assertEquals(1, count, "inhibit time must suppress the second event");
    }

    @Test
    void mappingChangeStopsOldFrames() throws Exception {
        producer.configurePdo(0, true,
                List.of(new MappingEntry(0x2001, 0, 16)),
                PdoTriggerType.EVENT, 0x1AC, 0, 0);
        network.broadcastNmt(NmtCommand.START.getCode(), 2);
        waitForState(producer, NmtState.OPERATIONAL);
        producer.getPdoManager().triggerEvent(0);
        Thread.sleep(50);

        network.broadcastNmt(NmtCommand.ENTER_PRE_OPERATIONAL.getCode(), 2);
        waitForState(producer, NmtState.PRE_OPERATIONAL);
        producer.configurePdo(0, true, List.of(), PdoTriggerType.EVENT, 0x1AC, 0, 0);
        network.broadcastNmt(NmtCommand.START.getCode(), 2);
        waitForState(producer, NmtState.OPERATIONAL);

        captured.clear();
        producer.getPdoManager().triggerEvent(0);
        Thread.sleep(100);
        assertTrue(captured.stream().noneMatch(f -> f.getCobId() == 0x1AC),
                "no frame may be sent after the mapping is removed");
    }

    @Test
    void syncAndTimerTriggersWork() throws Exception {
        producer.configurePdo(0, true,
                List.of(new MappingEntry(0x2001, 0, 16)),
                PdoTriggerType.SYNC, 0x1AD, 0, 0);
        producer.configurePdo(1, true,
                List.of(new MappingEntry(0x2000, 0, 16)),
                PdoTriggerType.TIMER, 0x282, 0, 60);
        network.broadcastNmt(NmtCommand.START.getCode(), 2);
        waitForState(producer, NmtState.OPERATIONAL);

        producer.getObjectDictionary().write(0x2001, 0, new byte[]{7, 0});
        captured.clear();
        network.getBus().send(new CanFrame(0x080, new byte[0], 0));
        Thread.sleep(220);

        long syncCount = captured.stream().filter(f -> f.getCobId() == 0x1AD).count();
        assertTrue(syncCount >= 1, "SYNC must trigger synchronous TPDO");

        long timerCount = captured.stream().filter(f -> f.getCobId() == 0x282).count();
        assertTrue(timerCount >= 2, "timer TPDO must fire periodically, was " + timerCount);
    }
}
