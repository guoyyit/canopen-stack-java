package com.github.guoyyit.canopenstackjava.can;

/**
 * 总线故障注入参数。相同 seed 下故障判定序列可复现。
 */
public final class FaultProfile {

    private final double dropProbability;
    private final double duplicateProbability;
    private final long delayMillis;
    private final long seed;

    public FaultProfile(double dropProbability, double duplicateProbability, long delayMillis, long seed) {
        this.dropProbability = dropProbability;
        this.duplicateProbability = duplicateProbability;
        this.delayMillis = delayMillis;
        this.seed = seed;
    }

    public static FaultProfile noFaults() {
        return new FaultProfile(0.0, 0.0, 0L, 0L);
    }

    public double getDropProbability() {
        return dropProbability;
    }

    public double getDuplicateProbability() {
        return duplicateProbability;
    }

    public long getDelayMillis() {
        return delayMillis;
    }

    public long getSeed() {
        return seed;
    }
}
