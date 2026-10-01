package com.github.guoyyit.canopenstackjava.network;

import com.github.guoyyit.canopenstackjava.can.CanFrame;
import com.github.guoyyit.canopenstackjava.can.FaultProfile;
import com.github.guoyyit.canopenstackjava.can.SimulatedBus;
import com.github.guoyyit.canopenstackjava.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.persist.NodeSnapshot;
import com.github.guoyyit.canopenstackjava.sdo.SdoClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 多节点仿真网络：所有节点共享同一条进程内模拟总线，按 COB-ID 寻址通信。
 * 网络持有一个共享调度器，节点各自使用独占单线程执行器串行化内部动作。
 */
public final class CanOpenNetwork {

    private final SimulatedBus bus;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Map<Integer, CanOpenNode> nodes = new HashMap<>();
    private final Map<Integer, SdoClient> sdoClients = new HashMap<>();

    public CanOpenNetwork(FaultProfile faultProfile, ScheduledExecutorService scheduler) {
        this.ownsScheduler = scheduler == null;
        this.scheduler = scheduler == null
                ? Executors.newScheduledThreadPool(2, networkThreadFactory())
                : scheduler;
        this.bus = new SimulatedBus(faultProfile == null ? FaultProfile.noFaults() : faultProfile,
                this.scheduler);
    }

    public SimulatedBus getBus() {
        return bus;
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public CanOpenNode createNode(int nodeId) {
        int id = nodeId & 0x7F;
        synchronized (nodes) {
            if (nodes.containsKey(id)) {
                throw new IllegalArgumentException("node " + id + " already exists");
            }
            CanOpenNode node = new CanOpenNode(id, bus, null);
            nodes.put(id, node);
            node.start();
            return node;
        }
    }

    public CanOpenNode getNode(int nodeId) {
        synchronized (nodes) {
            return nodes.get(nodeId & 0x7F);
        }
    }

    public Map<Integer, CanOpenNode> getNodes() {
        synchronized (nodes) {
            return Map.copyOf(nodes);
        }
    }

    public SdoClient createSdoClient(int clientNodeId) {
        return createSdoClient(clientNodeId, 1000L);
    }

    public SdoClient createSdoClient(int clientNodeId, long timeoutMillis) {
        int id = clientNodeId & 0x7F;
        SdoClient client = new SdoClient(id, bus, scheduler, timeoutMillis);
        client.start();
        synchronized (sdoClients) {
            sdoClients.put(id, client);
        }
        return client;
    }

    /** 在 0x000 发送 NMT 命令；targetNodeId=0 表示广播。 */
    public void broadcastNmt(int commandCode, int targetNodeId) {
        bus.send(new CanFrame(0x000,
                new byte[]{(byte) commandCode, (byte) (targetNodeId & 0x7F)}, 0));
    }

    public void setFaultProfile(FaultProfile profile) {
        bus.setFaultProfile(profile);
    }

    public Map<Integer, NodeSnapshot> shutdownAndSnapshot() {
        Map<Integer, NodeSnapshot> snapshots = new HashMap<>();
        synchronized (nodes) {
            for (CanOpenNode node : nodes.values()) {
                snapshots.put(node.getNodeId(), node.snapshot());
                node.shutdown();
            }
            nodes.clear();
        }
        shutdownResources();
        return snapshots;
    }

    public void shutdown() {
        synchronized (nodes) {
            for (CanOpenNode node : nodes.values()) {
                node.shutdown();
            }
            nodes.clear();
        }
        shutdownResources();
    }

    private void shutdownResources() {
        synchronized (sdoClients) {
            sdoClients.values().forEach(SdoClient::stop);
            sdoClients.clear();
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ThreadFactory networkThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "canopen-bus");
            thread.setDaemon(true);
            return thread;
        };
    }
}
