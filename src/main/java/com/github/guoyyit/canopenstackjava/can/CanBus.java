package com.github.guoyyit.canopenstackjava.can;

/**
 * 进程内模拟 CAN 总线。所有节点按 COB-ID 自行过滤接收，
 * 等价于真实 CAN 的广播 + 标识符寻址。
 */
public interface CanBus {

    void send(CanFrame frame);

    void addListener(FrameListener listener);

    void removeListener(FrameListener listener);

}
