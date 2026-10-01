package com.github.guoyyit.canopenstackjava.sim;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.guoyyit.canopenstackjava.can.FaultProfile;
import com.github.guoyyit.canopenstackjava.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.nmt.NmtState;

import java.util.LinkedHashMap;
import java.util.Map;

/** 多节点仿真的最小管理接口。 */
@RestController
@RequestMapping("/api/sim")
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam(defaultValue = "0") long seed) {
        simulationService.start(new FaultProfile(0, 0, 0, seed));
        return Map.of("status", "started", "seed", seed);
    }

    @PostMapping("/nodes")
    public Map<String, Object> createNode(@RequestParam int nodeId) {
        CanOpenNode node = simulationService.createNode(nodeId);
        return Map.of("nodeId", node.getNodeId(), "state", node.getState().name());
    }

    @GetMapping("/nodes")
    public Map<String, Object> nodes() {
        Map<String, Object> result = new LinkedHashMap<>();
        simulationService.getNodes().forEach((id, node) -> result.put(String.valueOf(id),
                Map.of("state", node.getState().name())));
        return result;
    }

    @PostMapping("/faults")
    public Map<String, Object> faults(@RequestParam(defaultValue = "0") double drop,
                                      @RequestParam(defaultValue = "0") double duplicate,
                                      @RequestParam(defaultValue = "0") long delayMillis,
                                      @RequestParam(defaultValue = "0") long seed) {
        simulationService.injectFaults(new FaultProfile(drop, duplicate, delayMillis, seed));
        return Map.of("drop", drop, "duplicate", duplicate, "delayMillis", delayMillis, "seed", seed);
    }

    @PostMapping("/restart")
    public Map<String, Object> restart() {
        simulationService.restart();
        Map<String, Object> states = new LinkedHashMap<>();
        for (Map.Entry<Integer, CanOpenNode> entry : simulationService.getNodes().entrySet()) {
            NmtState state = entry.getValue().getState();
            states.put(String.valueOf(entry.getKey()), state.name());
        }
        return Map.of("status", "restarted", "nodes", states);
    }
}
