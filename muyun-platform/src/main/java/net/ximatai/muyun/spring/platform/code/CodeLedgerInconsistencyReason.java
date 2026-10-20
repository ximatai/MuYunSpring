package net.ximatai.muyun.spring.platform.code;

public enum CodeLedgerInconsistencyReason {
    MATCHED,
    TARGET_UNAVAILABLE,
    OCCUPIED_BY_ANOTHER_RECORD,
    RECORD_MISSING,
    VALUE_EMPTY,
    VALUE_CHANGED
}
