package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Query semantics of a field UI control, independent of a concrete UI adapter. */
public enum FieldUiControlQueryMode implements CodeTitleEnum {
    DEFAULT("默认"),
    BETWEEN("区间");

    private final String title;

    FieldUiControlQueryMode(String title) {
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
