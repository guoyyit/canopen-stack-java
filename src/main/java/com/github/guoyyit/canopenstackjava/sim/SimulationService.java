package com.github.guoyyit.canopenstackjava.sim;

import com.github.guoyyit.canopenstackjava.can.FaultProfile;
import com.github.guoyyit.canopenstackjava.network.CanOpenNetwork;
import com.github.guoyyit.canopenstackjava.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.persist.NodeSnapshot;
import com.github.guoyyit.canopenstackjava.persist.PersistenceService;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 仿真服务门面：管理网络生命周期、节点创建、故障注入与重启恢复。
 */
public final class SimulationService {

    private final ScheduledExecutorService scheduler;
    private final PersistenceService persistence;

    private volatile CanOpenNetwork network;
    private volatile FaultProfile currentProfile = FaultProfile.noFaults();

    public SimulationService(ScheduledExecutorService scheduler, String persistencePath) {
        this.scheduler = scheduler;
        this.persistence = new PersistenceService(persistencePath);
    }

    public synchronized void start(FaultProfile profile) {
        if (network != null) {
            return;
        }
        this.currentProfile = profile == null ? FaultProfile.noFaults() : profile;
        this.network = new CanOpenNetwork(this.currentProfile, scheduler);
    }

    public CanOpenNode createNode(int nodeId) {
        return requireNetwork().createNode(nodeId);
    }

    public CanOpenNode getNode(int nodeId) {
        return requireNetwork().getNode(nodeId);
    }

    public Map<Integer, CanOpenNode> getNodes() {
        return requireNetwork().getNodes();
    }

    public CanOpenNetwork getNetwork() {
        return requireNetwork();
    }

    public synchronized void injectFaults(FaultProfile profile) {
        this.currentProfile = profile == null ? FaultProfile.noFaults() : profile;
        requireNetwork().setFaultProfile(this.currentProfile);
    }

    /** 停机：快照全部节点（状态 / OD / EMCY）并持久化。 */
    public synchronized void stopAndPersist() {
        if (network == null) {
            return;
        }
        Map<Integer, NodeSnapshot> snapshots = network.shutdownAndSnapshot();
        persistence.save(snapshots);
        network = null;
    }

    /**
     * 重启恢复：重建网络与节点并恢复状态 / OD / 紧急历史。
     * 所有分段传输会话全新创建，旧的在途传输被显式终止，绝不悬挂。
     */
    public synchronized void restart() {
        Map<Integer, NodeSnapshot> snapshots = persistence.load();
        if (network != null) {
            network.shutdown();
            network = null;
        }
        CanOpenNetwork rebuilt = new CanOpenNetwork(currentProfile, scheduler);
        for (NodeSnapshot snapshot : snapshots.values()) {
            CanOpenNode node = rebuilt.createNode(snapshot.getNodeId());
            node.restore(snapshot);
        }
        this.network = rebuilt;
    }

    public synchronized void shutdown() {
        if (network != null) {
            network.shutdown();
            network = null;
        }
    }

    private CanOpenNetwork requireNetwork() {
        CanOpenNetwork current = network;
        if (current == null) {
            throw new IllegalStateException("simulation not started");
        }
        return current;
    }
}
