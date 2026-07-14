package org.leo.jmg.mem.packer;

/** Packer 延迟初始化状态。 */
public enum PackerStatus {
    UNINITIALIZED("uninitialized"),
    AVAILABLE("available"),
    FAILED("failed");

    private final String value;

    PackerStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
