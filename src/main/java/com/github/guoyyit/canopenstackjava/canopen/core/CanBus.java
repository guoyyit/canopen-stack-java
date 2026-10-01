package com.github.guoyyit.canopenstackjava.canopen.core;

/**
 * 进程内模拟 CAN 总线。
 */
public interface CanBus {

    void attach(int nodeId, CanBusListener listener);

    void detach(int nodeId);

    void send(int senderNodeId, CanFrame frame);

    /**
     * 在总线串行锁内执行动作。网络中所有事件（收帧、定时、状态迁移）均在该锁内执行，
     * 从而保证全局事件顺序确定且不会出现跨节点锁序死锁。
     */
    void runExclusively(Runnable action);
}
