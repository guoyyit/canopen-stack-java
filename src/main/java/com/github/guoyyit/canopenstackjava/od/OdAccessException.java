package com.github.guoyyit.canopenstackjava.od;

/** 对象字典 / SDO 访问被拒绝时抛出，携带标准中止码。 */
public class OdAccessException extends RuntimeException {

    private final int abortCode;

    public OdAccessException(AbortCode abortCode) {
        super(abortCode.name());
        this.abortCode = abortCode.getCode();
    }

    public OdAccessException(AbortCode abortCode, String message) {
        super(message);
        this.abortCode = abortCode.getCode();
    }

    public int getAbortCode() {
        return abortCode;
    }
}
