package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.core.FaultInjection;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;
import com.github.guoyyit.canopenstackjava.canopen.od.OdException;
import com.github.guoyyit.canopenstackjava.canopen.pdo.PdoMapping;
import com.github.guoyyit.canopenstackjava.canopen.pdo.PdoType;
import com.github.guoyyit.canopenstackjava.canopen.pdo.SyncProducer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdoMappingTriggerTest {

    private CanNetwork newNetwork() {
        return new CanNetwork(new VirtualClock(false), new FaultInjection());
    }

    @Test
    void mappingRejectsMissingObjectAndExcessLength() {
        CanNetwork network = newNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();

        OdException missing = assertThrows(OdException.class,
                () -> node.configureTpdo(0, PdoType.EVENT_DRIVEN, 0L, 0L,
                        List.of(new PdoMapping(0x9999, 0, 8))));
        assertEquals(AbortCode.PDO_MAP_INVALID, missing.getAbortCode());

        List<PdoMapping> tooLong = List.of(
                new PdoMapping(0x2004, 0, 32),
                new PdoMapping(0x2005, 0, 32),
                new PdoMapping(0x2002, 0, 16));
        OdException excess = assertThrows(OdException.class,
                () -> node.configureTpdo(0, PdoType.EVENT_DRIVEN, 0L, 0L, tooLong));
        assertEquals(AbortCode.PDO_LENGTH_EXCEEDED, excess.getAbortCode());

        network.shutdown();
    }

    @Test
    void eventDrivenTpdoPacksAndRpdoUnpacksAcrossNodes() {
        CanNetwork network = newNetwork();
        CanOpenNode producer = network.createNode(1);
        CanOpenNode consumer = network.createNode(2);
        producer.start();
        consumer.start();

        int cobId = producer.tpdo(0).getCobId();
        producer.configureTpdo(0, PdoType.EVENT_DRIVEN, 0L, 0L,
                List.of(new PdoMapping(0x2002, 0, 16)));
        consumer.configureRpdo(0, List.of(new PdoMapping(0x2002, 0, 16)));
        // RPDO 默认 COB-ID 按本节点编号，需要与生产者 TPDO COB-ID 对齐。
        consumer.getObjectDictionary().write(0x1400, 1, (long) cobId);

        producer.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.OPERATIONAL);
        consumer.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.OPERATIONAL);

        producer.getObjectDictionary().write(0x2002, 0, 0x1234L);
        producer.tpdo(0).triggerEvent();

        assertEquals(0x1234L, consumer.getObjectDictionary().read(0x2002, 0));
        network.shutdown();
    }

    @Test
    void timerAndSyncTriggersFireOnTick() {
        CanNetwork network = newNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        node.configureTpdo(0, PdoType.TIMER_DRIVEN, 10L, 0L,
                List.of(new PdoMapping(0x2000, 0, 8)));
        node.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.OPERATIONAL);
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);

        network.tick(9);
        assertEquals(0, recorder.withId(node.tpdo(0).getCobId()).size());
        network.tick(1);
        assertEquals(1, recorder.withId(node.tpdo(0).getCobId()).size());
        network.tick(10);
        assertEquals(2, recorder.withId(node.tpdo(0).getCobId()).size());

        // 同步触发：SYNC 帧驱动 SYNCHRONOUS PDO。
        recorder.clear();
        node.configureTpdo(1, PdoType.SYNCHRONOUS, 0L, 0L,
                List.of(new PdoMapping(0x2001, 0, 8)));
        SyncProducer sync = new SyncProducer(network.getBus(), 100);
        sync.sendSync();
        assertEquals(1, recorder.withId(node.tpdo(1).getCobId()).size());

        network.shutdown();
    }

    @Test
    void inhibitTimeSuppressesTooFrequentEvents() {
        CanNetwork network = newNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        // 抑制时间 10ms（参数内部以 100us 单位，这里直接以微秒给出）。
        node.configureTpdo(0, PdoType.EVENT_DRIVEN, 0L, 10_000L,
                List.of(new PdoMapping(0x2000, 0, 8)));
        node.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.OPERATIONAL);
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);

        node.tpdo(0).triggerEvent();
        node.tpdo(0).triggerEvent();
        node.tpdo(0).triggerEvent();
        assertEquals(1, recorder.withId(node.tpdo(0).getCobId()).size());

        network.tick(10);
        node.tpdo(0).triggerEvent();
        assertEquals(2, recorder.withId(node.tpdo(0).getCobId()).size());

        network.shutdown();
    }

    @Test
    void remappedTpdoNeverUsesOldMappingAfterChange() {
        CanNetwork network = newNetwork();
        CanOpenNode node = network.createNode(1);
        node.start();
        node.configureTpdo(0, PdoType.EVENT_DRIVEN, 0L, 0L,
                List.of(new PdoMapping(0x2000, 0, 8)));
        node.requestState(com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState.OPERATIONAL);
        FrameRecorder recorder = FrameRecorder.attach(network.getBus(), 100);

        node.getObjectDictionary().write(0x2000, 0, 0xAAL);
        node.tpdo(0).triggerEvent();
        CanFrame first = recorder.withId(node.tpdo(0).getCobId()).get(0);
        assertEquals(0xAA, first.getU8(0));

        // 原子切换为新映射（16 位条目，小端序），之后载荷必须来自新映射。
        node.getObjectDictionary().write(0x2002, 0, 0xBEEFL);
        node.configureTpdo(0, PdoType.EVENT_DRIVEN, 0L, 0L,
                List.of(new PdoMapping(0x2002, 0, 16)));
        node.tpdo(0).triggerEvent();
        List<CanFrame> frames = recorder.withId(node.tpdo(0).getCobId());
        CanFrame second = frames.get(frames.size() - 1);
        assertEquals(2, second.getDataLength());
        assertEquals(0xEF, second.getU8(0));
        assertEquals(0xBE, second.getU8(1));

        network.shutdown();
    }
}
