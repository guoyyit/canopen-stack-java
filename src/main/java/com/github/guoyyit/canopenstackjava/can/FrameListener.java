package com.github.guoyyit.canopenstackjava.can;

/** 总线帧监听器。 */
@FunctionalInterface
public interface FrameListener {
    void onFrame(CanFrame frame);
}
