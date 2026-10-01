package com.github.guoyyit.canopenstackjava.nmt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** NMT 状态机：INITIALISATION -> PRE_OPERATIONAL -> OPERATIONAL / STOPPED。 */
public final class NmtStateMachine {

    /** apply() 的结果，供调用方在锁与监听器派发之外执行复位动作。 */
    public enum ApplyResult {
        IGNORED,
        STATE_CHANGED,
        NODE_RESET,
        COMMUNICATION_RESET
    }

    private final CopyOnWriteArrayList<NmtListener> listeners = new CopyOnWriteArrayList<>();
    private NmtState state = NmtState.INITIALISATION;

    public NmtState getState() {
        synchronized (this) {
            return state;
        }
    }

    public void addListener(NmtListener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(NmtListener listener) {
        listeners.remove(listener);
    }

    /** 初始化完成：进入 PRE_OPERATIONAL。 */
    public void enterPreOperational() {
        List<Runnable> notifications = new ArrayList<>();
        synchronized (this) {
            transition(NmtState.PRE_OPERATIONAL, notifications);
        }
        dispatch(notifications);
    }

    /** 应用一条 NMT 命令；初始化态只接受复位命令。 */
    public boolean apply(NmtCommand command) {
        return applyDetailed(command) != ApplyResult.IGNORED;
    }

    /**
     * 与 {@link #apply(NmtCommand)} 相同，但区分普通迁移与复位命令，
     * 使节点能在状态机派发完成后再终止 SDO/PDO、bootup 并回到 PRE_OPERATIONAL。
     */
    public ApplyResult applyDetailed(NmtCommand command) {
        if (command == null) {
            return ApplyResult.IGNORED;
        }
        List<Runnable> notifications = new ArrayList<>();
        ApplyResult result;
        synchronized (this) {
            result = applyLocked(command, notifications);
        }
        dispatch(notifications);
        return result;
    }

    /** 重启恢复时直接装载状态，不触发回调。 */
    public void restoreState(NmtState restored) {
        synchronized (this) {
            this.state = restored == null ? NmtState.PRE_OPERATIONAL : restored;
        }
    }

    private ApplyResult applyLocked(NmtCommand command, List<Runnable> notifications) {
        if (state == NmtState.INITIALISATION
                && command != NmtCommand.RESET_NODE && command != NmtCommand.RESET_COMMUNICATION) {
            return ApplyResult.IGNORED;
        }
        switch (command) {
            case START:
                transition(NmtState.OPERATIONAL, notifications);
                return ApplyResult.STATE_CHANGED;
            case STOP:
                transition(NmtState.STOPPED, notifications);
                return ApplyResult.STATE_CHANGED;
            case ENTER_PRE_OPERATIONAL:
                transition(NmtState.PRE_OPERATIONAL, notifications);
                return ApplyResult.STATE_CHANGED;
            case RESET_NODE:
                transition(NmtState.INITIALISATION, notifications);
                notifications.add(() -> listeners.forEach(NmtListener::nodeReset));
                return ApplyResult.NODE_RESET;
            case RESET_COMMUNICATION:
                transition(NmtState.INITIALISATION, notifications);
                notifications.add(() -> listeners.forEach(NmtListener::communicationReset));
                return ApplyResult.COMMUNICATION_RESET;
            default:
                return ApplyResult.IGNORED;
        }
    }

    private void transition(NmtState target, List<Runnable> notifications) {
        NmtState old = this.state;
        if (old == target) {
            return;
        }
        this.state = target;
        notifications.add(() -> {
            for (NmtListener listener : listeners) {
                listener.stateChanged(old, target);
            }
        });
    }

    private void dispatch(List<Runnable> notifications) {
        notifications.forEach(Runnable::run);
    }
}
