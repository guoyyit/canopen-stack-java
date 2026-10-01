package com.github.guoyyit.canopenstackjava.nmt;

/** NMT 状态迁移监听。 */
public interface NmtListener {

    void stateChanged(NmtState oldState, NmtState newState);

    default void nodeReset() {
    }

    default void communicationReset() {
    }
}
