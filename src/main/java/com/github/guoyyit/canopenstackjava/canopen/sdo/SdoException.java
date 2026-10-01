package com.github.guoyyit.canopenstackjava.canopen.sdo;

import com.github.guoyyit.canopenstackjava.canopen.od.AbortCode;

/**
 * SDO 客户端传输失败异常，携带服务器中止码或本地超时原因。
 */
public class SdoException extends RuntimeException {

    private final AbortCode abortCode;

    public SdoException(AbortCode abortCode, String message) {
        super(message);
        this.abortCode = abortCode;
    }

    public AbortCode getAbortCode() {
        return abortCode;
    }
}
