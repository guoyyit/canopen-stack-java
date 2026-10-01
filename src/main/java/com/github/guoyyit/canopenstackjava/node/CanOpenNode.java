package com.github.guoyyit.canopenstackjava.node;

import com.github.guoyyit.canopenstackjava.can.CanBus;
import com.github.guoyyit.canopenstackjava.can.CanFrame;
import com.github.guoyyit.canopenstackjava.can.FrameListener;
import com.github.guoyyit.canopenstackjava.emcy.EmergencyHistory;
import com.github.guoyyit.canopenstackjava.emcy.EmergencyRecord;
import com.github.guoyyit.canopenstackjava.emcy.EmergencyService;
import com.github.guoyyit.canopenstackjava.hb.HeartbeatProducer;
import com.github.guoyyit.canopenstackjava.nmt.NmtCommand;
import com.github.guoyyit.canopenstackjava.nmt.NmtListener;
import com.github.guoyyit.canopenstackjava.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.nmt.NmtStateMachine;
import com.github.guoyyit.canopenstackjava.od.ObjectDictionary;
import com.github.guoyyit.canopenstackjava.od.OdEntry;
import com.github.guoyyit.canopenstackjava.od.StandardDictionary;
import com.github.guoyyit.canopenstackjava.pdo.MappingEntry;
import com.github.guoyyit.canopenstackjava.pdo.PdoManager;
import com.github.guoyyit.canopenstackjava.pdo.PdoTriggerType;
import com.github.guoyyit.canopenstackjava.persist.EmergencySnapshot;
import com.github.guoyyit.canopenstackjava.persist.EntrySnapshot;
import com.github.guoyyit.canopenstackjava.persist.NodeSnapshot;
import com.github.guoyyit.canopenstackjava.sdo.SdoServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 一个 CANopen 从节点。
 *
 * <p>节点内所有会改变状态的动作（NMT 命令、SDO/PDO/心跳帧处理、复位、
 * 定时器回调）都在节点独占的单线程调度器上串行执行，因此“状态迁移
 * 与在途传输并发”的结果等价于一个确定的全序，不会出现状态不一致。
 */
public final class CanOpenNode {

    private static final int EMERGENCY_HISTORY_CAPACITY = 32;

    private final int nodeId;
    private final CanBus bus;
    private final CanBus nodeBus;
    private final boolean ownsExecutor;
    private final ScheduledExecutorService executor;

    private final NmtStateMachine nmt = new NmtStateMachine();
    private final ObjectDictionary od;
    private final EmergencyHistory emergencyHistory = new EmergencyHistory(EMERGENCY_HISTORY_CAPACITY);
    private final EmergencyService emergency;
    private final SdoServer sdoServer;
    private final PdoManager pdoManager;
    private final HeartbeatProducer heartbeat;

    private volatile boolean started;

    public CanOpenNode(int nodeId, CanBus bus, ScheduledExecutorService sharedScheduler) {
        this.nodeId = nodeId & 0x7F;
        this.bus = bus;
        this.nodeBus = new com.github.guoyyit.canopenstackjava.can.NodeBus(bus, this.nodeId);
        if (sharedScheduler == null) {
            this.ownsExecutor = true;
            this.executor = Executors.newSingleThreadScheduledExecutor(nodeThreadFactory());
        } else {
            this.ownsExecutor = false;
            this.executor = sharedScheduler;
        }
        this.od = StandardDictionary.create(this.nodeId);
        this.emergency = new EmergencyService(this.nodeId, nodeBus, emergencyHistory);
        this.sdoServer = new SdoServer(this.nodeId, nodeBus, od, nmt, this.executor, 1000L)
                .withManagedDispatch();
        this.pdoManager = new PdoManager(this.nodeId, nodeBus, od, nmt, this.executor)
                .withManagedDispatch();
        this.heartbeat = new HeartbeatProducer(this.nodeId, nodeBus, nmt, this.executor, 0L);
        registerNmtHandler();
    }

    public int getNodeId() {
        return nodeId;
    }

    public NmtState getState() {
        return nmt.getState();
    }

    public ObjectDictionary getObjectDictionary() {
        return od;
    }

    public NmtStateMachine getNmt() {
        return nmt;
    }

    public SdoServer getSdoServer() {
        return sdoServer;
    }

    public PdoManager getPdoManager() {
        return pdoManager;
    }

    public HeartbeatProducer getHeartbeatProducer() {
        return heartbeat;
    }

    public EmergencyService getEmergencyService() {
        return emergency;
    }

    /** 上电：初始化完成后发送 bootup 并进入 PRE_OPERATIONAL。 */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        sdoServer.start();
        pdoManager.start();
        registerBusDispatcher();
        heartbeat.start();
        nmt.enterPreOperational();
        heartbeat.sendBootup();
    }

    /** 同步语义的 NMT 命令：等待迁移与复位处理全部完成后再返回。 */
    public void nmtCommand(NmtCommand command) {
        try {
            executor.submit(() -> dispatchNmt(command)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("NMT command failed", e);
        }
    }

    /** 处理总线上到达的 NMT 命令帧（0x000）。 */
    public void handleNmtFrame(CanFrame frame) {
        if (frame.getCobId() != 0x000 || frame.getDlc() < 2) {
            return;
        }
        byte[] d = frame.dataBytes();
        int target = d[1] & 0xFF;
        if (target != 0 && target != nodeId) {
            return;
        }
        NmtCommand command = NmtCommand.fromCode(d[0] & 0xFF);
        if (command != null) {
            dispatchNmt(command);
        }
    }

    private void dispatchNmt(NmtCommand command) {
        NmtStateMachine.ApplyResult result = nmt.applyDetailed(command);
        if (result == NmtStateMachine.ApplyResult.NODE_RESET
                || result == NmtStateMachine.ApplyResult.COMMUNICATION_RESET) {
            performReset();
        }
    }

    public void configurePdo(int number, boolean transmit, List<MappingEntry> entries,
                             PdoTriggerType trigger, int cobId, long inhibitMicros, long timerPeriodMillis) {
        pdoManager.configure(number, transmit, entries, trigger, cobId, inhibitMicros, timerPeriodMillis);
    }

    /** 保存节点状态、可持久化 OD 数据与紧急历史。 */
    public NodeSnapshot snapshot() {
        NodeSnapshot snapshot = new NodeSnapshot();
        snapshot.setNodeId(nodeId);
        snapshot.setState(nmt.getState());
        List<EntrySnapshot> entrySnapshots = new ArrayList<>();
        for (OdEntry entry : od.entries()) {
            entrySnapshots.add(new EntrySnapshot(entry.getIndex(), entry.getSubIndex(), entry.getData()));
        }
        snapshot.setEntries(entrySnapshots);
        List<int[]> records = new ArrayList<>();
        List<byte[]> fields = new ArrayList<>();
        for (EmergencyRecord record : emergencyHistory.getAll()) {
            records.add(new int[]{(int) record.getTimestampMillis(),
                    record.getErrorCode(), record.getErrorRegister()});
            fields.add(record.getManufacturerField());
        }
        snapshot.setEmergency(new EmergencySnapshot(records, fields));
        return snapshot;
    }

    /** 重启恢复：装载 OD / 状态 / EMCY；在途分段传输通过重新初始化服务端而终止。 */
    public void restore(NodeSnapshot snapshot) {
        if (snapshot.getEntries() != null) {
            for (EntrySnapshot entrySnapshot : snapshot.getEntries()) {
                if (od.contains(entrySnapshot.index(), entrySnapshot.subIndex())) {
                    od.restoreWrite(entrySnapshot.index(), entrySnapshot.subIndex(),
                            entrySnapshot.data());
                }
            }
        }
        if (snapshot.getEmergency() != null) {
            emergencyHistory.clear();
            List<int[]> records = snapshot.getEmergency().records();
            List<byte[]> fields = snapshot.getEmergency().fields();
            for (int i = 0; i < records.size(); i++) {
                int[] r = records.get(i);
                emergencyHistory.add(new EmergencyRecord(r[0], r[1], r[2],
                        i < fields.size() ? fields.get(i) : new byte[0]));
            }
        }
        sdoServer.reset();
        pdoManager.reset();
        nmt.restoreState(snapshot.getState());
        long heartbeatPeriod = readHeartbeatPeriod();
        heartbeat.setPeriodMillis(heartbeatPeriod);
    }

    public void shutdown() {
        heartbeat.stop();
        pdoManager.stop();
        sdoServer.stop();
        bus.removeListener(this.dispatcher);
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    /** 节点内部串行化：所有总线帧经节点执行器路由。 */
    private final FrameListener dispatcher = this::dispatchFrame;

    private void registerBusDispatcher() {
        bus.addListener(dispatcher);
    }

    private void dispatchFrame(CanFrame frame) {
        if (frame.getSenderNodeId() == nodeId) {
            return;
        }
        try {
            executor.execute(() -> {
                handleNmtFrame(frame);
                if (frame.getCobId() == 0x600 + nodeId) {
                    sdoServer.onFrame(frame);
                } else if (frame.getCobId() == 0x080 || isPdoRange(frame.getCobId())) {
                    pdoManager.onFrame(frame);
                }
            });
        } catch (RuntimeException ignored) {
            // 执行器已关闭（停机中），丢弃后续帧
        }
    }

    private boolean isPdoRange(int cobId) {
        // PDO 标识区间 0x180-0x5FF；是否属于本节点由 PdoManager 按激活配置匹配 COB-ID
        return cobId >= 0x180 && cobId <= 0x5FF;
    }

    private void registerNmtHandler() {
        nmt.addListener(new NmtListener() {
            @Override
            public void stateChanged(NmtState oldState, NmtState newState) {
                heartbeat.sendState(newState);
            }
        });
    }

    /**
     * 复位处理（在节点独占线程上，且在状态迁移回调派发完成之后执行）：
     * 先终止在途 SDO 分段传输与 PDO 活动，再恢复通信默认值并 bootup 进入 PRE_OPERATIONAL。
     */
    private void performReset() {
        terminateCommunication();
        // 节点复位与通信复位都恢复通信默认参数；应用数据（含 EMCY 历史）保留
        StandardDictionary.resetCommunication(od);
        restartAfterReset();
    }

    private void terminateCommunication() {
        // 终止在途分段传输（向对端 abort）与 PDO 活动，避免悬挂
        sdoServer.reset();
        pdoManager.reset();
        heartbeat.setPeriodMillis(readHeartbeatPeriod());
    }

    private void restartAfterReset() {
        heartbeat.sendBootup();
        nmt.enterPreOperational();
    }

    private long readHeartbeatPeriod() {
        if (!od.contains(0x1017, 0)) {
            return 0L;
        }
        byte[] raw = od.read(0x1017, 0);
        return (raw[0] & 0xFFL) | ((raw[1] & 0xFFL) << 8);
    }

    private ThreadFactory nodeThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "canopen-node-" + nodeId);
            thread.setDaemon(true);
            return thread;
        };
    }
}
