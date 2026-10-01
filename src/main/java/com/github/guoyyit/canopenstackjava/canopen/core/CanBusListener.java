package com.github.guoyyit.canopenstackjava.canopen.core;

/**
 * CAN 总线帧接收回调。
 */
public interface CanBusListener {

    void onFrame(CanFrame frame);
}
