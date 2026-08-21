package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/**
 * Describes the value contract of a field UI control independently from a concrete UI adapter.
 */
public enum FieldUiControlValueShape implements CodeTitleEnum {
    SCALAR("标量"),
    COLLECTION("集合"),
    COMPOSITE("组合值");

    private final String title;

    FieldUiControlValueShape(String title) {
        this.title = title;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getTitle() {
        return title;
    }
}
