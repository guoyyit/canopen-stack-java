package com.github.guoyyit.canopenstackjava.pdo;

import com.github.guoyyit.canopenstackjava.can.CanBus;
import com.github.guoyyit.canopenstackjava.can.CanFrame;
import com.github.guoyyit.canopenstackjava.can.FrameListener;
import com.github.guoyyit.canopenstackjava.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.nmt.NmtStateMachine;
import com.github.guoyyit.canopenstackjava.od.AbortCode;
import com.github.guoyyit.canopenstackjava.od.OdAccessException;
import com.github.guoyyit.canopenstackjava.od.ObjectDictionary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * PDO 管理器（CiA 301 最小实现）。
 *
 * <ul>
 *   <li>映射校验：条目存在、TPDO 可读 / RPDO 可写、可映射、总长度 ≤ 64 位；</li>
 *   <li>触发：事件（0xFF 类）、定时器（0x100）、SYNC（0/1..240）；</li>
 *   <li>事件与定时触发受抑制时间约束；</li>
 *   <li>映射变更立即生效，旧版本的在途/已调度帧一律丢弃；</li>
 *   <li>PDO 仅在 OPERATIONAL 状态收发。</li>
 * </ul>
 */
public final class PdoManager implements FrameListener {

    private static final int MAX_PDOS = 4;
    private static final int SYNC_COB_ID = 0x080;

    private final int nodeId;
    private final CanBus bus;
    private final ObjectDictionary od;
    private final NmtStateMachine nmt;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    private final PdoMapping[] tpdo = new PdoMapping[MAX_PDOS];
    private final PdoMapping[] rpdo = new PdoMapping[MAX_PDOS];
    private final PdoChannel[] tpdoChannels = new PdoChannel[MAX_PDOS];
    private final PdoChannel[] rpdoChannels = new PdoChannel[MAX_PDOS];
    private final long[] lastTpdoSendNanos = new long[MAX_PDOS];
    private final Map<Integer, ScheduledFuture<?>> timerTasks = new HashMap<>();

    private volatile boolean running;
    private volatile boolean selfRegistered = true;

    public PdoManager(int nodeId, CanBus bus, ObjectDictionary od, NmtStateMachine nmt,
                      ScheduledExecutorService scheduler) {
        this.nodeId = nodeId & 0x7F;
        this.bus = bus;
        this.od = od;
        this.nmt = nmt;
        this.scheduler = scheduler;
        for (int i = 0; i < MAX_PDOS; i++) {
            tpdo[i] = new PdoMapping(i, true);
            rpdo[i] = new PdoMapping(i, false);
            tpdo[i].setCobId(0x180 + 0x100 * i + this.nodeId);
            rpdo[i].setCobId(0x200 + 0x100 * i + this.nodeId);
            lastTpdoSendNanos[i] = 0L;
        }
    }

    /** 受管模式：帧由节点执行器统一串行分发。 */
    public PdoManager withManagedDispatch() {
        this.selfRegistered = false;
        return this;
    }

    public void start() {
        if (selfRegistered) {
            bus.addListener(this);
        }
        running = true;
    }

    public void stop() {
        running = false;
        if (selfRegistered) {
            bus.removeListener(this);
        }
        reset();
    }

    /** 通信复位：取消全部定时器，去激活映射，结果确定且可恢复。 */
    public void reset() {
        synchronized (lock) {
            cancelTimerTasks();
            for (int i = 0; i < MAX_PDOS; i++) {
                tpdo[i].deactivate();
                rpdo[i].deactivate();
                tpdoChannels[i] = null;
                rpdoChannels[i] = null;
                lastTpdoSendNanos[i] = 0L;
            }
        }
    }

    /**
     * 配置 PDO。仅在 PRE_OPERATIONAL 允许变更；条目存在性 / 可映射性 /
     * 长度上限在此统一校验。空映射等价于去激活。
     */
    public void configure(int number, boolean transmit, List<MappingEntry> entries,
                          PdoTriggerType trigger, int cobId, long inhibitMicros, long timerPeriodMillis) {
        if (number < 0 || number >= MAX_PDOS) {
            throw new OdAccessException(AbortCode.PARAMETER_INCOMPATIBILITY, "PDO number out of range");
        }
        if (nmt.getState() != NmtState.PRE_OPERATIONAL) {
            throw new OdAccessException(AbortCode.UNSUPPORTED_ACCESS,
                    "PDO mapping can only be changed in PRE_OPERATIONAL");
        }
        List<MappingEntry> safe = entries == null ? List.of() : List.copyOf(entries);
        validateMapping(safe, transmit);

        synchronized (lock) {
            PdoMapping mapping = transmit ? tpdo[number] : rpdo[number];
            cancelTimerTask(txKey(number));
            if (safe.isEmpty()) {
                mapping.deactivate();
                if (transmit) {
                    tpdoChannels[number] = null;
                } else {
                    rpdoChannels[number] = null;
                }
                return;
            }
            mapping.beginConfiguration();
            mapping.setCobId(cobId);
            mapping.setMappingEntries(safe);
            mapping.setTriggerType(trigger == null ? PdoTriggerType.EVENT : trigger);
            mapping.setInhibitTimeMicros(inhibitMicros);
            mapping.setTimerPeriodMillis(timerPeriodMillis);
            mapping.activate();
            if (transmit) {
                tpdoChannels[number] = new PdoChannel(mapping, od);
                scheduleTimerIfNeeded(number, mapping);
            } else {
                rpdoChannels[number] = new PdoChannel(mapping, od);
            }
        }
    }

    public PdoMapping getMapping(int number, boolean transmit) {
        synchronized (lock) {
            return transmit ? tpdo[number] : rpdo[number];
        }
    }

    /** 应用层事件触发指定 TPDO（受状态与抑制时间约束）。 */
    public void triggerEvent(int tpdoNumber) {
        synchronized (lock) {
            if (!running || nmt.getState() != NmtState.OPERATIONAL) {
                return;
            }
            PdoMapping mapping = tpdo[tpdoNumber];
            if (!mapping.isActive() || mapping.getTriggerType() != PdoTriggerType.EVENT) {
                return;
            }
            if (!inhibitElapsed(tpdoNumber, mapping)) {
                return;
            }
            sendTpdoLocked(tpdoNumber, mapping, tpdoChannels[tpdoNumber]);
        }
    }

    /** SYNC 帧到达：在 OPERATIONAL 发送所有 synchronous TPDO。 */
    public void onSync() {
        synchronized (lock) {
            if (!running || nmt.getState() != NmtState.OPERATIONAL) {
                return;
            }
            for (int i = 0; i < MAX_PDOS; i++) {
                PdoMapping mapping = tpdo[i];
                if (mapping.isActive() && mapping.getTriggerType() == PdoTriggerType.SYNC) {
                    sendTpdoLocked(i, mapping, tpdoChannels[i]);
                }
            }
        }
    }

    @Override
    public void onFrame(CanFrame frame) {
        if (!running) {
            return;
        }
        if (frame.getCobId() == SYNC_COB_ID) {
            onSync();
            return;
        }
        if (nmt.getState() != NmtState.OPERATIONAL) {
            return;
        }
        synchronized (lock) {
            for (int i = 0; i < MAX_PDOS; i++) {
                PdoMapping mapping = rpdo[i];
                if (mapping.isActive() && mapping.getCobId() == frame.getCobId()) {
                    PdoChannel channel = rpdoChannels[i];
                    if (channel != null) {
                        channel.unpack(frame.dataBytes());
                    }
                    return;
                }
            }
        }
    }

    private void sendTpdoLocked(int number, PdoMapping mapping, PdoChannel channel) {
        if (channel == null) {
            return;
        }
        byte[] payload = channel.pack();
        lastTpdoSendNanos[number] = System.nanoTime();
        bus.send(new CanFrame(mapping.getCobId(), payload, 0));
    }

    private boolean inhibitElapsed(int number, PdoMapping mapping) {
        long inhibitNanos = TimeUnit.MICROSECONDS.toNanos(mapping.getInhibitTimeMicros());
        if (inhibitNanos <= 0L) {
            return true;
        }
        return System.nanoTime() - lastTpdoSendNanos[number] >= inhibitNanos;
    }

    private void scheduleTimerIfNeeded(int number, PdoMapping mapping) {
        if (mapping.getTriggerType() != PdoTriggerType.TIMER
                || mapping.getTimerPeriodMillis() <= 0L) {
            return;
        }
        long version = mapping.getVersion();
        long periodMillis = mapping.getTimerPeriodMillis();
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            synchronized (lock) {
                if (!running || nmt.getState() != NmtState.OPERATIONAL) {
                    return;
                }
                PdoMapping current = tpdo[number];
                if (!current.isActive() || current.getVersion() != version) {
                    return;
                }
                if (!inhibitElapsed(number, current)) {
                    return;
                }
                sendTpdoLocked(number, current, tpdoChannels[number]);
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        timerTasks.put(txKey(number), task);
    }

    private void validateMapping(List<MappingEntry> entries, boolean transmit) {
        int totalBits = PdoChannel.totalBits(entries);
        for (MappingEntry entry : entries) {
            if (!od.contains(entry.index(), entry.subIndex())) {
                throw new OdAccessException(AbortCode.OBJECT_DOES_NOT_EXIST,
                        "mapped object 0x" + Integer.toHexString(entry.index())
                                + "/" + entry.subIndex() + " does not exist");
            }
            if (!od.getEntry(entry.index(), entry.subIndex()).getType().isFixedSize()) {
                throw new OdAccessException(AbortCode.CANNOT_MAP_TO_PDO,
                        "variable-length object cannot be mapped");
            }
            if (transmit && !od.getEntry(entry.index(), entry.subIndex()).getAccess().canRead()) {
                throw new OdAccessException(AbortCode.CANNOT_MAP_TO_PDO,
                        "mapped TPDO object is not readable");
            }
            if (!transmit && !od.getEntry(entry.index(), entry.subIndex()).getAccess().canWrite()) {
                throw new OdAccessException(AbortCode.CANNOT_MAP_TO_PDO,
                        "mapped RPDO object is not writable");
            }
        }
        if (entries.size() > MAX_PDOS * 2) {
            throw new OdAccessException(AbortCode.PDO_LENGTH_EXCEEDED, "too many mapped entries");
        }
    }

    private static int txKey(int number) {
        return number + 1;
    }

    private void cancelTimerTask(int key) {
        ScheduledFuture<?> task = timerTasks.remove(key);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void cancelTimerTasks() {
        List<ScheduledFuture<?>> tasks = new ArrayList<>(timerTasks.values());
        timerTasks.clear();
        tasks.forEach(t -> t.cancel(false));
    }
}
