package com.github.guoyyit.canopenstackjava.sdo;

/** SDO 传输失败异常，携带标准中止码。 */
public class SdoException extends RuntimeException {

    private final int abortCode;

    public SdoException(int abortCode, String message) {
        super(message);
        this.abortCode = abortCode;
    }

    public int getAbortCode() {
        return abortCode;
    }
}
