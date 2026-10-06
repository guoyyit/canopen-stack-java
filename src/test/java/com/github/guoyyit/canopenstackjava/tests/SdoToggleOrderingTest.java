package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.canopen.core.CanFrame;
import com.github.guoyyit.canopenstackjava.canopen.network.CanNetwork;
import com.github.guoyyit.canopenstackjava.canopen.node.CanOpenNode;
import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;
import com.github.guoyyit.canopenstackjava.canopen.sdo.SdoClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手工构造 SDO 帧验证 toggle 校验与重复段处理（CiA 301）：
 * 合法重复按相同结果幂等处理，真正乱序以可区分中止码拒绝且双方不残留会话。
 */
class SdoToggleOrderingTest {

    private static final int REQ = 0x601;
    private static final int RESP = 0x581;

    private CanNetwork network;
    private CanOpenNode node;
    private FrameRecorder recorder;

    private void setUp() {
        network = new CanNetwork();
        node = network.createNode(1);
        node.start();
        recorder = FrameRecorder.attach(network.getBus(), 100);
    }

    private void tearDown() {
        network.shutdown();
    }

    private void sendToServer(int... data) {
        byte[] payload = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            payload[i] = (byte) data[i];
        }
        network.getBus().send(0, new CanFrame(REQ, payload));
    }

    private List<CanFrame> responses() {
        return recorder.withId(RESP);
    }

    private static int abortCodeOf(CanFrame frame) {
        return frame.getU8(4) | (frame.getU8(5) << 8) | (frame.getU8(6) << 16)
                | (frame.getU8(7) << 24);
    }

    @Test
    void outOfOrderDownloadSegmentIsRejectedWithToggleAbort() {
        setUp();
        // 分段下载 initiate（20 字节），随后首段直接携带 toggle=1（乱序）。
        sendToServer(0x21, 0x00, 0x21, 0x00, 20, 0, 0, 0);
        assertEquals(1, node.getSdoServer().activeSessionCount());

        sendToServer(0x10, 1, 2, 3, 4, 5, 6, 7); // toggle=1，期望 0

        List<CanFrame> responses = responses();
        assertEquals(2, responses.size());
        assertEquals(0x80, responses.get(1).getU8(0) & 0xFF);
        assertEquals(AbortCode.TOGGLE_BIT_INVALID.getCode(), abortCodeOf(responses.get(1)));
        assertEquals(0, node.getSdoServer().activeSessionCount(), "拒绝后服务器不得残留会话");

        // 拒绝后立即重发同一传输必须成功。
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        byte[] blob = new byte[20];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i + 1);
        }
        client.download(1, 0x2100, 0, blob);
        assertArrayEquals(blob, client.upload(1, 0x2100, 0));
        client.shutdown();
        tearDown();
    }

    @Test
    void outOfOrderUploadRequestIsRejectedWithToggleAbort() {
        setUp();
        node.getObjectDictionary().write(0x2100, 0, new byte[20]);

        sendToServer(0x40, 0x00, 0x21, 0x00, 0, 0, 0, 0); // initiate upload
        assertEquals(1, node.getSdoServer().activeSessionCount());

        sendToServer(0x70, 0, 0, 0, 0, 0, 0, 0); // 首个段请求 toggle=1（乱序）

        List<CanFrame> responses = responses();
        assertEquals(2, responses.size());
        assertEquals(0x80, responses.get(1).getU8(0) & 0xFF);
        assertEquals(AbortCode.TOGGLE_BIT_INVALID.getCode(), abortCodeOf(responses.get(1)));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 拒绝后新上传立即成功且数据完整。
        SdoClient client = new SdoClient(network.getBus(), network.getClock());
        assertEquals(20, client.upload(1, 0x2100, 0).length);
        client.shutdown();
        tearDown();
    }

    @Test
    void duplicateDownloadSegmentIsAckedIdenticallyWithoutDoubleAccumulation() {
        setUp();
        // initiate 下载 14 字节。
        sendToServer(0x21, 0x00, 0x21, 0x00, 14, 0, 0, 0);
        // 第一段 toggle=0，随后原样重复一次（合法重复）。
        sendToServer(0x00, 1, 2, 3, 4, 5, 6, 7);
        sendToServer(0x00, 1, 2, 3, 4, 5, 6, 7);
        // 第二段 toggle=1 且为末段。
        sendToServer(0x11, 8, 9, 10, 11, 12, 13, 14);

        List<CanFrame> responses = responses();
        // initiate ack + 段0 ack + 重复段0 重发 ack + 段1 ack。
        assertEquals(4, responses.size());
        assertEquals(0x20, responses.get(1).getU8(0) & 0xFF, "段0 ack toggle=0");
        assertEquals(0x20, responses.get(2).getU8(0) & 0xFF, "重复段0 必须重发相同 ack");
        assertEquals(0x30, responses.get(3).getU8(0) & 0xFF, "段1 ack toggle=1");

        // 数据不得重复累积：恰好 14 字节。
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14},
                (byte[]) node.getObjectDictionary().read(0x2100, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());
        tearDown();
    }

    @Test
    void duplicateUploadRequestIsAnsweredWithSameSegmentWithoutAdvancing() {
        setUp();
        byte[] blob = new byte[14];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i + 1);
        }
        node.getObjectDictionary().write(0x2100, 0, blob);

        sendToServer(0x40, 0x00, 0x21, 0x00, 0, 0, 0, 0); // initiate upload
        sendToServer(0x60, 0, 0, 0, 0, 0, 0, 0);          // 段请求 toggle=0
        sendToServer(0x60, 0, 0, 0, 0, 0, 0, 0);          // 合法重复
        sendToServer(0x70, 0, 0, 0, 0, 0, 0, 0);          // 段请求 toggle=1（末段）

        List<CanFrame> responses = responses();
        assertEquals(4, responses.size());
        // 首段应答与其重复必须逐字节一致（相同结果，不推进）。
        for (int i = 0; i < 8; i++) {
            assertEquals(responses.get(1).getU8(i), responses.get(2).getU8(i),
                    "duplicate upload request must be answered with identical segment");
        }
        assertEquals(0x00, responses.get(1).getU8(0) & 0xFF, "首段 toggle=0");
        assertEquals(0x11, responses.get(3).getU8(0) & 0xFF, "末段 toggle=1 且 c=1");
        assertEquals(0, node.getSdoServer().activeSessionCount());
        tearDown();
    }

    @Test
    void duplicateOfFinalSegmentIsAbsorbedByTombstone() {
        setUp();
        // 5 字节下载：单段即末段。末段重复必须按相同结果回答，不得报协议错误。
        sendToServer(0x21, 0x00, 0x21, 0x00, 5, 0, 0, 0);
        sendToServer(0x05, 1, 2, 3, 4, 5, 0, 0); // toggle=0, unused=2, c=1
        sendToServer(0x05, 1, 2, 3, 4, 5, 0, 0); // 合法重复末段

        List<CanFrame> responses = responses();
        assertEquals(3, responses.size());
        assertEquals(0x20, responses.get(1).getU8(0) & 0xFF);
        assertEquals(0x20, responses.get(2).getU8(0) & 0xFF,
                "末段重复必须重发相同 ack 而非中止");
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5},
                (byte[]) node.getObjectDictionary().read(0x2100, 0));
        assertEquals(0, node.getSdoServer().activeSessionCount());

        // 5 字节上传：单段请求即末次请求，重复同样被墓碑吸收。
        sendToServer(0x40, 0x00, 0x21, 0x00, 0, 0, 0, 0);
        sendToServer(0x60, 0, 0, 0, 0, 0, 0, 0);
        sendToServer(0x60, 0, 0, 0, 0, 0, 0, 0);
        List<CanFrame> all = responses();
        assertEquals(6, all.size());
        assertEquals(0x05, all.get(4).getU8(0) & 0xFF, "末段应答 toggle=0, unused=2, c=1");
        assertEquals(0x05, all.get(5).getU8(0) & 0xFF, "末次请求重复必须重发相同末段");
        assertEquals(0, node.getSdoServer().activeSessionCount());
        tearDown();
    }

    @Test
    void segmentWithoutSessionIsRejectedDistinguishably() {
        setUp();
        // 无任何会话时收到段帧：以命令符中止码拒绝（与 toggle 错误可区分）。
        sendToServer(0x00, 1, 2, 3, 4, 5, 6, 7);
        List<CanFrame> responses = responses();
        assertEquals(1, responses.size());
        assertEquals(0x80, responses.get(0).getU8(0) & 0xFF);
        assertEquals(AbortCode.SDO_COMMAND_SPECIFIER_INVALID.getCode(),
                abortCodeOf(responses.get(0)));
        assertTrue(AbortCode.SDO_COMMAND_SPECIFIER_INVALID.getCode()
                != AbortCode.TOGGLE_BIT_INVALID.getCode(), "两种拒绝必须可区分");
        assertEquals(0, node.getSdoServer().activeSessionCount());
        tearDown();
    }
}
