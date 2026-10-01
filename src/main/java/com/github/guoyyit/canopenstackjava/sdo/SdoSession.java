package com.github.guoyyit.canopenstackjava.sdo;

import java.io.ByteArrayOutputStream;

/**
 * SDO 分段传输会话。服务端每个客户端连接持有一个，
 * 客户端每个目标服务端持有一个。所有方法由所属组件在锁内调用。
 */
final class SdoSession {

    static final int IDLE = 0;
    static final int DOWNLOAD_INIT = 1;
    static final int DOWNLOAD_SEGMENT = 2;
    static final int UPLOAD_INIT = 3;
    static final int UPLOAD_SEGMENT = 4;

    private int state = IDLE;
    private int index;
    private int subIndex;
    private int toggle;
    private long totalSize = -1L;

    private final ByteArrayOutputStream downloadBuffer = new ByteArrayOutputStream();
    private byte[] uploadData;
    private int uploadOffset;

    SdoSession() {
    }

    int getState() {
        return state;
    }

    int getIndex() {
        return index;
    }

    int getSubIndex() {
        return subIndex;
    }

    int getToggle() {
        return toggle;
    }

    void flipToggle() {
        toggle ^= 1;
    }

    long getTotalSize() {
        return totalSize;
    }

    void beginDownload(int idx, int sub, long size) {
        this.state = DOWNLOAD_INIT;
        this.index = idx;
        this.subIndex = sub;
        this.toggle = 0;
        this.totalSize = size;
        this.downloadBuffer.reset();
    }

    void beginDownloadSegments() {
        this.state = DOWNLOAD_SEGMENT;
    }

    void appendDownload(byte[] chunk) {
        downloadBuffer.write(chunk, 0, chunk.length);
    }

    byte[] downloadBytes() {
        return downloadBuffer.toByteArray();
    }

    int downloadLength() {
        return downloadBuffer.size();
    }

    void beginUpload(int idx, int sub, byte[] data) {
        this.state = UPLOAD_INIT;
        this.index = idx;
        this.subIndex = sub;
        this.toggle = 0;
        this.uploadData = data;
        this.uploadOffset = 0;
    }

    void beginUploadSegments() {
        this.state = UPLOAD_SEGMENT;
    }

    int uploadOffset() {
        return uploadOffset;
    }

    byte[] uploadData() {
        return uploadData;
    }

    void advanceUpload(int bytes) {
        uploadOffset += bytes;
    }

    boolean uploadComplete() {
        return uploadData != null && uploadOffset >= uploadData.length;
    }

    boolean isActive() {
        return state != IDLE;
    }

    void reset() {
        state = IDLE;
        index = 0;
        subIndex = 0;
        toggle = 0;
        totalSize = -1L;
        downloadBuffer.reset();
        uploadData = null;
        uploadOffset = 0;
    }
}
