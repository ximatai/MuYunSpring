package net.ximatai.muyun.spring.platform.application;

/** Human-reviewed realization of one versioned requirement; never an executable field definition. */
public record ApplicationConstructionRequirement(Section section, int index, String objectKey,
                                                Mode mode, String fieldName, String explanation) {
    public enum Section { SCOPE, RULE, RELATION }
    public enum Mode { FIELD, REQUIRED, UNIQUE, MANUAL, UNSUPPORTED }
    public ApplicationConstructionRequirement {
        if (section == null || index < 0 || index >= 16 || mode == null
                || objectKey == null || !objectKey.matches("[a-z][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("需求兑现项身份无效");
        if (explanation == null || explanation.isBlank() || explanation.length() > 500)
            throw new IllegalArgumentException("请说明本项如何兑现及仍需人工核对的内容");
        explanation = explanation.trim();
        fieldName = fieldName == null ? "" : fieldName.trim();
        boolean field = mode == Mode.FIELD || mode == Mode.REQUIRED || mode == Mode.UNIQUE;
        if (field ? !fieldName.matches("[a-z][a-zA-Z0-9_]{0,63}") : !fieldName.isEmpty())
            throw new IllegalArgumentException("配置检查须绑定字段，人工或未支持项不能伪装为字段检查");
        if (section == Section.RELATION && mode != Mode.UNSUPPORTED)
            throw new IllegalArgumentException("当前建设尚不能兑现对象关联，请将后续关系移入暂不建设范围");
    }
}
