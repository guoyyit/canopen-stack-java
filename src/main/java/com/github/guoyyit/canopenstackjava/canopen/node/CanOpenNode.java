package com.github.guoyyit.canopenstackjava.canopen.node;

import com.github.guoyyit.canopenstackjava.canopen.core.CanBus;
import com.github.guoyyit.canopenstackjava.canopen.core.CanBusListener;
import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.emcy.EmergencyHistory;
import com.github.guoyyit.canopenstackjava.canopen.emcy.EmergencyProducer;
import com.github.guoyyit.canopenstackjava.canopen.emcy.HeartbeatMonitor;
import com.github.guoyyit.canopenstackjava.canopen.emcy.HeartbeatProducer;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtCommand;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtMaster;
import com.github.guoyyit.canopenstackjava.canopen.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.canopen.od.ObjectDictionary;
import com.github.guoyyit.canopenstackjava.canopen.pdo.PdoMapping;
import com.github.guoyyit.canopenstackjava.canopen.pdo.PdoType;
import com.github.guoyyit.canopenstackjava.canopen.pdo.Rpdo;
import com.github.guoyyit.canopenstackjava.canopen.pdo.SyncProducer;
import com.github.guoyyit.canopenstackjava.canopen.pdo.Tpdo;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoServer;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 CANopen 节点：组合对象字典、NMT 状态机、SDO 服务器、PDO、
 * 心跳与紧急报文生产者，所有外部事件入口均在总线串行锁内执行以保证并发确定性。
 */
public class CanOpenNode {

    static final int PDO_CHANNEL_COUNT = 4;

    private final int nodeId;
    private final CanBus bus;
    private final VirtualClock clock;
    private final NodeConfig config;
    private final ObjectDictionary objectDictionary = new ObjectDictionary();
    private final EmergencyHistory emergencyHistory = new EmergencyHistory();
    private final EmergencyProducer emergencyProducer;
    private final HeartbeatProducer heartbeatProducer;
    private final SdoServer sdoServer;
    private final Tpdo[] tpdos = new Tpdo[PDO_CHANNEL_COUNT];
    private final Rpdo[] rpdos = new Rpdo[PDO_CHANNEL_COUNT];
    private final List<HeartbeatMonitor> heartbeatMonitors = new ArrayList<>();
    private final CanBusListener busListener;

    private volatile NmtState state = NmtState.INITIALISATION;
    private boolean running;

    public CanOpenNode(int nodeId, CanBus bus, VirtualClock clock) {
        this(nodeId, bus, clock, new NodeConfig());
    }

    public CanOpenNode(int nodeId, CanBus bus, VirtualClock clock, NodeConfig config) {
        if (nodeId < 1 || nodeId > 127) {
            throw new IllegalArgumentException("node id must be 1..127, got " + nodeId);
        }
        this.nodeId = nodeId;
        this.bus = bus;
        this.clock = clock;
        this.config = config;
        DefaultDictionary.populate(this);
        this.emergencyProducer = new EmergencyProducer(this, emergencyHistory);
        this.heartbeatProducer = new HeartbeatProducer(this);
        this.sdoServer = new SdoServer(this);
        for (int i = 0; i < PDO_CHANNEL_COUNT; i++) {
            tpdos[i] = new Tpdo(this, i);
            rpdos[i] = new Rpdo(this, i);
        }
        this.busListener = this::handleFrame;
    }

    public int getNodeId() {
        return nodeId;
    }

    public NmtState getState() {
        return state;
    }

    public ObjectDictionary getObjectDictionary() {
        return objectDictionary;
    }

    public EmergencyHistory getEmergencyHistory() {
        return emergencyHistory;
    }

    public EmergencyProducer getEmergencyProducer() {
        return emergencyProducer;
    }

    public SdoServer getSdoServer() {
        return sdoServer;
    }

    public Tpdo tpdo(int number) {
        return tpdos[checkPdoNumber(number)];
    }

    public Rpdo rpdo(int number) {
        return rpdos[checkPdoNumber(number)];
    }

    public void configureTpdo(int number, PdoType type, long eventTimerMillis,
                              long inhibitTimeMicros, List<PdoMapping> mappings) {
        bus.runExclusively(() -> tpdos[checkPdoNumber(number)]
                .configure(type, eventTimerMillis, inhibitTimeMicros, mappings));
    }

    public void configureRpdo(int number, List<PdoMapping> mappings) {
        bus.runExclusively(() -> rpdos[checkPdoNumber(number)].configure(mappings));
    }

    public void start() {
        bus.runExclusively(() -> {
            bus.attach(nodeId, busListener);
            sdoServer.start();
            running = true;
            enterState(NmtState.PRE_OPERATIONAL);
        });
    }

    public void shutdown() {
        bus.runExclusively(() -> {
            if (!running) {
                return;
            }
            running = false;
            sdoServer.abortAllSessions();
            sdoServer.shutdown();
            bus.detach(nodeId);
        });
    }

    public void onFrame(CanFrame frame) {
        bus.runExclusively(() -> handleFrame(frame));
    }

    public void onTick(long nowMillis) {
        bus.runExclusively(() -> tick(nowMillis));
    }

    /**
     * 监控对端节点心跳；守护窗口内未收到心跳时触发回调（在总线串行锁内调用）。
     */
    public HeartbeatMonitor monitorHeartbeat(int monitoredNodeId, long guardWindowMillis,
                                             java.util.function.BiConsumer<Integer, Long> callback) {
        final HeartbeatMonitor[] created = new HeartbeatMonitor[1];
        bus.runExclusively(() -> {
            HeartbeatMonitor monitor = new HeartbeatMonitor(
                    nodeId, monitoredNodeId, guardWindowMillis, callback);
            heartbeatMonitors.add(monitor);
            created[0] = monitor;
        });
        return created[0];
    }

    /** 线程安全地产生紧急事件（在总线串行锁内执行，保证历史不丢失）。 */
    public void raiseEmergency(int emergencyCode) {
        bus.runExclusively(() -> emergencyProducer.raiseError(emergencyCode));
    }

    public void applyNmtCommand(NmtCommand command) {
        bus.runExclusively(() -> handleNmt(command));
    }

    public void requestState(NmtState target) {
        bus.runExclusively(() -> enterState(target));
    }

    public NodeSnapshot snapshot() {
        return NodePersistence.snapshot(this);
    }

    public void restore(NodeSnapshot snapshot) {
        bus.runExclusively(() -> NodePersistence.restore(this, snapshot));
    }

    // ---- 包内协作方法（均假设已持有总线串行锁）----

    public CanBus getBus() {
        return bus;
    }

    public VirtualClock getClock() {
        return clock;
    }

    public NodeConfig getConfig() {
        return config;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public void attachToBus() {
        bus.attach(nodeId, busListener);
    }

    public void detachFromBus() {
        bus.detach(nodeId);
    }

    public void tick(long nowMillis) {
        heartbeatProducer.onTick(nowMillis);
        for (HeartbeatMonitor monitor : heartbeatMonitors) {
            monitor.onTick(nowMillis);
        }
        if (state == NmtState.OPERATIONAL) {
            for (Tpdo tpdo : tpdos) {
                tpdo.onTick(nowMillis);
            }
        }
        sdoServer.onTick(nowMillis);
    }

    public void enterState(NmtState newState) {
        if (newState == state) {
            return;
        }
        state = newState;
        heartbeatProducer.onStateChanged(clock.nowMillis());
        if (newState != NmtState.OPERATIONAL) {
            for (Tpdo tpdo : tpdos) {
                tpdo.onLeaveOperational();
            }
        }
    }

    public void handleNmt(NmtCommand command) {
        switch (command) {
            case ENTER_OPERATIONAL -> enterState(NmtState.OPERATIONAL);
            case STOP -> enterState(NmtState.STOPPED);
            case ENTER_PRE_OPERATIONAL -> enterState(NmtState.PRE_OPERATIONAL);
            case RESET_NODE -> resetNode();
            case RESET_COMMUNICATION -> resetCommunication();
        }
    }

    public void resetNode() {
        sdoServer.abortAllSessions();
        enterState(NmtState.INITIALISATION);
        for (Tpdo tpdo : tpdos) {
            tpdo.onLeaveOperational();
        }
        enterState(NmtState.PRE_OPERATIONAL);
    }

    public void resetCommunication() {
        sdoServer.abortAllSessions();
        for (Tpdo tpdo : tpdos) {
            tpdo.onLeaveOperational();
        }
        enterState(NmtState.INITIALISATION);
        enterState(NmtState.PRE_OPERATIONAL);
    }

    private void handleFrame(CanFrame frame) {
        int cobId = frame.getId();
        // SYNC 为零数据长度帧，在长度校验之前处理。
        if (cobId == SyncProducer.SYNC_COB_ID) {
            if (state == NmtState.OPERATIONAL) {
                for (Tpdo tpdo : tpdos) {
                    tpdo.onSync();
                }
            }
            return;
        }
        if (frame.getDataLength() < 1) {
            return;
        }
        if (cobId >= 0x701 && cobId <= 0x77F) {
            int producerNodeId = cobId - 0x700;
            for (HeartbeatMonitor monitor : heartbeatMonitors) {
                monitor.onHeartbeat(producerNodeId, clock.nowMillis());
            }
            return;
        }
        if (cobId == NmtMaster.NMT_COB_ID && frame.getDataLength() >= 2
                && (frame.getU8(1) == nodeId || frame.getU8(1) == 0)) {
            NmtCommand command = decodeNmtCommand(frame.getU8(0));
            if (command != null) {
                handleNmt(command);
            }
            return;
        }
        switch (state) {
            case PRE_OPERATIONAL -> {
                if (cobId == 0x600 + nodeId) {
                    sdoServer.onFrame(frame);
                }
            }
            case OPERATIONAL -> {
                if (cobId == 0x600 + nodeId) {
                    sdoServer.onFrame(frame);
                } else {
                    for (Rpdo rpdo : rpdos) {
                        if (rpdo.handles(cobId)) {
                            rpdo.onFrame(frame);
                            break;
                        }
                    }
                }
            }
            case STOPPED, INITIALISATION -> {
                // 停止/初始化态：除 NMT 外不处理业务报文。
            }
        }
    }

    private static NmtCommand decodeNmtCommand(int code) {
        for (NmtCommand command : NmtCommand.values()) {
            if (command.getCode() == code) {
                return command;
            }
        }
        return null;
    }

    private static int checkPdoNumber(int number) {
        if (number < 0 || number >= PDO_CHANNEL_COUNT) {
            throw new IllegalArgumentException("PDO number out of range 0.."
                    + (PDO_CHANNEL_COUNT - 1));
        }
        return number;
    }
}
