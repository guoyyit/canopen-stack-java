package com.github.guoyyit.canopenstackjava.sim;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** 仿真服务的 Spring 装配。 */
@Configuration
public class SimulationConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService canOpenScheduler() {
        return Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "canopen-sim");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(destroyMethod = "shutdown")
    public SimulationService simulationService(ScheduledExecutorService canOpenScheduler) {
        return new SimulationService(canOpenScheduler,
                System.getProperty("canopen.persistence.path", "data/canopen-sim.json"));
    }
}
