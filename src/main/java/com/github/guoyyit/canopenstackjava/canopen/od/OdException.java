package com.github.guoyyit.canopenstackjava.canopen.od;

/**
 * 对象字典/SDO 访问异常，携带标准中止码。
 */
public class OdException extends RuntimeException {

    private final AbortCode abortCode;

    public OdException(AbortCode abortCode) {
        super(abortCode.name());
        this.abortCode = abortCode;
    }

    public OdException(AbortCode abortCode, String message) {
        super(message);
        this.abortCode = abortCode;
    }

    public AbortCode getAbortCode() {
        return abortCode;
    }
}
