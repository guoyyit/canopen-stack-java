package com.github.guoyyit.canopenstackjava.canopen.network;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.canopenstackjava.canopen.core.FaultInjection;
import com.github.guoyyit.canopenstackjava.canopen.core.SimulatedCanBus;
import com.github.guoyyit.canopenstackjava.canopen.core.VirtualClock;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.node.NodeSnapshot;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 多节点仿真网络：统一管理模拟总线、节点、时间步进、实时调度与 JSON 持久化。
 *
 * <p>时间模型有两种：</p>
 * <ul>
 *   <li>虚拟时钟（默认）：时间只随 {@link #tick(long)} 推进，事件顺序完全确定；</li>
 *   <li>实时模式：{@link #startRealtime()} 启动守护线程固定步进，供演示与真实节奏运行。</li>
 * </ul>
 */
public class CanNetwork {

    static final long TICK_MILLIS = 2L;

    private final VirtualClock clock;
    private final FaultInjection faults;
    private final SimulatedCanBus bus;
    private final TreeMap<Integer, CanOpenNode> nodes = new TreeMap<>();

    private volatile Thread realtimeThread;
    private volatile boolean realtimeRunning;

    public CanNetwork() {
        this(new VirtualClock(false), new FaultInjection());
    }

    public CanNetwork(VirtualClock clock, FaultInjection faults) {
        this.clock = clock;
        this.faults = faults;
        this.bus = new SimulatedCanBus(clock, faults);
    }

    public VirtualClock getClock() {
        return clock;
    }

    public FaultInjection getFaults() {
        return faults;
    }

    public SimulatedCanBus getBus() {
        return bus;
    }

    public CanOpenNode createNode(int nodeId) {
        final CanOpenNode[] created = new CanOpenNode[1];
        bus.runExclusively(() -> {
            if (nodes.containsKey(nodeId)) {
                throw new IllegalArgumentException("duplicate node id " + nodeId);
            }
            CanOpenNode node = new CanOpenNode(nodeId, bus, clock);
            nodes.put(nodeId, node);
            created[0] = node;
        });
        return created[0];
    }

    public CanOpenNode node(int nodeId) {
        return nodes.get(nodeId);
    }

    public List<CanOpenNode> nodes() {
        return new ArrayList<>(nodes.values());
    }

    /** 推进虚拟时钟并在总线串行锁内统一处理到期延迟帧与全部节点定时事件。 */
    public void tick(long deltaMillis) {
        clock.advance(deltaMillis);
        bus.runExclusively(() -> {
            long now = clock.nowMillis();
            bus.dispatchPending(now);
            for (CanOpenNode node : nodes.values()) {
                node.tick(now);
            }
        });
    }

    /** 启动守护线程按 {@link #TICK_MILLIS} 步进；虚拟时钟下时钟也由该线程推进。 */
    public void startRealtime() {
        if (realtimeRunning) {
            return;
        }
        realtimeRunning = true;
        realtimeThread = new Thread(() -> {
            long lastWall = System.currentTimeMillis();
            while (realtimeRunning) {
                try {
                    Thread.sleep(TICK_MILLIS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long nowWall = System.currentTimeMillis();
                long delta = nowWall - lastWall;
                lastWall = nowWall;
                try {
                    if (clock.isVirtual()) {
                        tick(Math.max(TICK_MILLIS, delta));
                    } else {
                        bus.runExclusively(() -> {
                            long now = clock.nowMillis();
                            bus.dispatchPending(now);
                            for (CanOpenNode node : nodes.values()) {
                                node.tick(now);
                            }
                        });
                    }
                } catch (RuntimeException ex) {
                    // 调度异常不得杀死仿真线程。
                    System.getLogger(CanNetwork.class.getName())
                            .log(System.Logger.Level.WARNING, "network tick failed", ex);
                }
            }
        }, "canopen-network-tick");
        realtimeThread.setDaemon(true);
        realtimeThread.start();
    }

    public void stopRealtime() {
        realtimeRunning = false;
        Thread thread = realtimeThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        realtimeThread = null;
    }

    public void save(File file) {
        List<NodeSnapshot> snapshots = new ArrayList<>();
        bus.runExclusively(() -> {
            for (CanOpenNode node : nodes.values()) {
                snapshots.add(node.snapshot());
            }
        });
        try {
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file, snapshots);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static CanNetwork load(File file) {
        CanNetwork network = new CanNetwork();
        network.loadInto(file);
        return network;
    }

    public void loadInto(File file) {
        List<NodeSnapshot> snapshots;
        try {
            snapshots = new ObjectMapper().readValue(file, new TypeReference<List<NodeSnapshot>>() {
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        bus.runExclusively(() -> {
            for (NodeSnapshot snapshot : snapshots) {
                CanOpenNode node;
                if (nodes.containsKey(snapshot.nodeId)) {
                    node = nodes.get(snapshot.nodeId);
                } else {
                    node = new CanOpenNode(snapshot.nodeId, bus, clock);
                    nodes.put(snapshot.nodeId, node);
                }
                node.attachToBus();
                node.setRunning(true);
                node.getSdoServer().start();
                node.restore(snapshot);
            }
        });
    }

    public void shutdown() {
        stopRealtime();
        bus.runExclusively(() -> {
            for (CanOpenNode node : nodes.values()) {
                node.shutdown();
            }
            nodes.clear();
            bus.shutdown();
        });
    }

}
