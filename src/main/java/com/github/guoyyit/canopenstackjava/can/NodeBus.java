package com.github.guoyyit.canopenstackjava.can;

/**
 * 节点侧总线视图：自动为节点发出的帧标记发送方节点 ID，
 * 使节点能像真实 CAN 控制器一样忽略自身 TX 回环。
 */
public final class NodeBus implements CanBus {

    private final CanBus delegate;
    private final int nodeId;

    public NodeBus(CanBus delegate, int nodeId) {
        this.delegate = delegate;
        this.nodeId = nodeId;
    }

    @Override
    public void send(CanFrame frame) {
        delegate.send(new CanFrame(frame.getCobId(), frame.dataBytes(), frame.getSequence(), nodeId));
    }

    @Override
    public void addListener(FrameListener listener) {
        delegate.addListener(listener);
    }

    @Override
    public void removeListener(FrameListener listener) {
        delegate.removeListener(listener);
    }
}
