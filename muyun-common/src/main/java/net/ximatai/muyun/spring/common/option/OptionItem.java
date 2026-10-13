package net.ximatai.muyun.spring.common.option;

import net.ximatai.muyun.spring.common.i18n.TextReference;

import net.ximatai.muyun.spring.common.util.Preconditions;

public record OptionItem(
        String code,
        String title,
        boolean enabled,
        Integer sortOrder,
        String parentCode,
        TextReference titleSource,
        net.ximatai.muyun.spring.common.i18n.TextPresentation titleText
) {
    public OptionItem(String code, String title, boolean enabled, Integer sortOrder, String parentCode, TextReference titleSource) {
        this(code, title, enabled, sortOrder, parentCode, titleSource, null);
    }
    public OptionItem(String code, String title, boolean enabled, Integer sortOrder, String parentCode) {
        this(code, title, enabled, sortOrder, parentCode, null, null);
    }

    public OptionItem {
        code = Preconditions.requireText(code, "code");
        title = Preconditions.requireText(title, "title");
    }
}
