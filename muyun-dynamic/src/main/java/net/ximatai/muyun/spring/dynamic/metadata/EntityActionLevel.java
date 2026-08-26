package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum EntityActionLevel implements CodeTitleEnum {
    LIST("LIST", "列表动作"),
    RECORD("RECORD", "记录动作"),
    BATCH("BATCH", "批量动作"),
    ANY("ANY", "任意层级");

    private final String code;
    private final String title;

    EntityActionLevel(String code, String title) {
        this.code = code;
        this.title = title;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getTitle() {
        return title;
    }
}
