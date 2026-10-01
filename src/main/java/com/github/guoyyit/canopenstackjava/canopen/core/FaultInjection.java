package com.github.guoyyit.canopenstackjava.canopen.core;

import java.util.Random;

/**
 * 总线故障注入策略：丢帧、重复帧、延迟帧，使用固定种子随机数保证可复现。
 */
public class FaultInjection {

    private long seed = 0L;
    private Random random = new Random(0L);
    private double dropRate = 0.0;
    private double duplicateRate = 0.0;
    private long minDelayMillis = 0L;
    private long maxDelayMillis = 0L;

    public void setSeed(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    public void setDropRate(double dropRate) {
        this.dropRate = clampRate(dropRate);
    }

    public void setDuplicateRate(double duplicateRate) {
        this.duplicateRate = clampRate(duplicateRate);
    }

    public void setDelayRange(long minDelayMillis, long maxDelayMillis) {
        if (minDelayMillis < 0 || maxDelayMillis < minDelayMillis) {
            throw new IllegalArgumentException("invalid delay range");
        }
        this.minDelayMillis = minDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    public boolean shouldDrop() {
        return dropRate > 0.0 && random.nextDouble() < dropRate;
    }

    public boolean shouldDuplicate() {
        return duplicateRate > 0.0 && random.nextDouble() < duplicateRate;
    }

    public long nextDelayMillis() {
        if (maxDelayMillis <= 0L) {
            return 0L;
        }
        if (maxDelayMillis == minDelayMillis) {
            return minDelayMillis;
        }
        long range = maxDelayMillis - minDelayMillis + 1L;
        return minDelayMillis + (Math.abs(random.nextLong()) % range);
    }

    public void reset() {
        this.random = new Random(seed);
    }

    public double getDropRate() {
        return dropRate;
    }

    public double getDuplicateRate() {
        return duplicateRate;
    }

    public boolean isActive() {
        return dropRate > 0.0 || duplicateRate > 0.0 || maxDelayMillis > 0L;
    }

    private static double clampRate(double rate) {
        if (rate < 0.0) {
            return 0.0;
        }
        if (rate > 1.0) {
            return 1.0;
        }
        return rate;
    }
}
