package com.github.guoyyit.canopenstackjava.od;

/** SDO / 对象字典标准中止码（CiA 301）。 */
public enum AbortCode {
    TIMEOUT(0x05040000, "SDO protocol timed out"),
    INVALID_COMMAND_SPECIFIER(0x05040001, "Client/server command specifier not valid or unknown"),
    INVALID_BLOCK_SIZE(0x05040002, "Invalid block size"),
    INVALID_SEQUENCE(0x05040003, "Invalid sequence number"),
    OUT_OF_MEMORY(0x05040005, "Out of memory"),
    UNSUPPORTED_ACCESS(0x06010000, "Unsupported access to an object"),
    READ_WRITE_ONLY(0x06010001, "Attempt to read a write-only object"),
    WRITE_READ_ONLY(0x06010002, "Attempt to write a read-only object"),
    OBJECT_DOES_NOT_EXIST(0x06020000, "Object does not exist in the object dictionary"),
    CANNOT_MAP_TO_PDO(0x06030000, "Object cannot be mapped to the PDO"),
    PDO_LENGTH_EXCEEDED(0x06040041, "PDO length exceeded"),
    PARAMETER_INCOMPATIBILITY(0x06040042, "General parameter incompatibility reason"),
    INTERNAL_INCOMPATIBILITY(0x06040043, "General internal incompatibility in device"),
    HARDWARE_ERROR(0x06060000, "Access failed due to hardware error"),
    TYPE_MISMATCH_LENGTH(0x06070010, "Data type mismatch, length of service parameter does not match"),
    TYPE_MISMATCH_TOO_HIGH(0x06070012, "Data type mismatch, length of service parameter too high"),
    TYPE_MISMATCH_TOO_LOW(0x06070013, "Data type mismatch, length of service parameter too low"),
    SUBINDEX_DOES_NOT_EXIST(0x06090011, "Sub-index does not exist"),
    VALUE_RANGE_EXCEEDED(0x06090030, "Value range of parameter exceeded"),
    RESOURCE_UNAVAILABLE(0x060A0023, "Resource not available: SDO connection"),
    GENERAL_ERROR(0x08000000, "General error"),
    DATA_NOT_TRANSFERABLE(0x08000020, "Data cannot be transferred or stored to the application"),
    DATA_DICTIONARY_STORE(0x08000024, "Data dictionary storage failure");

    private final int code;
    private final String description;

    AbortCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static AbortCode fromCode(int code) {
        for (AbortCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
