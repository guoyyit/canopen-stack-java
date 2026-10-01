package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.CanBus;
import com.github.guoyyit.canopenstackjava.canopen.core.CanBusListener;
import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * 挂在总线上的第三方观察者，按 COB-ID 记录报文，供测试断言使用。
 */
final class FrameRecorder implements CanBusListener {

    private final List<CanFrame> frames = new ArrayList<>();

    static FrameRecorder attach(CanBus bus, int recorderId) {
        FrameRecorder recorder = new FrameRecorder();
        bus.attach(recorderId, recorder);
        return recorder;
    }

    @Override
    public void onFrame(CanFrame frame) {
        synchronized (frames) {
            frames.add(frame);
        }
    }

    List<CanFrame> all() {
        synchronized (frames) {
            return new ArrayList<>(frames);
        }
    }

    List<CanFrame> withId(int cobId) {
        List<CanFrame> result = new ArrayList<>();
        synchronized (frames) {
            for (CanFrame frame : frames) {
                if (frame.getId() == cobId) {
                    result.add(frame);
                }
            }
        }
        return result;
    }

    void clear() {
        synchronized (frames) {
            frames.clear();
        }
    }
}
