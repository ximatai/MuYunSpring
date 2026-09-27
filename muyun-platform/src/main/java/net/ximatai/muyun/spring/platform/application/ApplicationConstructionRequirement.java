package net.ximatai.muyun.spring.platform.application;

/** Human-reviewed realization of one versioned requirement; never an executable field definition. */
public record ApplicationConstructionRequirement(Section section, int index, String objectKey,
                                                Mode mode, String fieldName, String explanation, Reference reference) {
    public enum Section { SCOPE, RULE, RELATION }
    public enum Mode { FIELD, REQUIRED, UNIQUE, REFERENCE, MANUAL, UNSUPPORTED }
    public record Reference(String objectKey, String moduleAlias) {
        public Reference {
            objectKey = objectKey == null ? "" : objectKey.trim();
            moduleAlias = moduleAlias == null ? "" : moduleAlias.trim();
            if (objectKey.isEmpty() == moduleAlias.isEmpty()) throw new IllegalArgumentException("引用须选择方案对象或已有模块之一");
            if (!objectKey.isEmpty() && !objectKey.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("引用对象标识无效");
            if (!moduleAlias.isEmpty()) net.ximatai.muyun.spring.common.util.PlatformNameRules.requireModuleAlias(moduleAlias);
        }
    }
    public ApplicationConstructionRequirement {
        if (section == null || index < 0 || index >= 16 || mode == null
                || objectKey == null || !objectKey.matches("[a-z][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("需求兑现项身份无效");
        if (explanation == null || explanation.isBlank() || explanation.length() > 500)
            throw new IllegalArgumentException("请说明本项如何兑现及仍需人工核对的内容");
        explanation = explanation.trim();
        fieldName = fieldName == null ? "" : fieldName.trim();
        boolean field = mode == Mode.FIELD || mode == Mode.REQUIRED || mode == Mode.UNIQUE || mode == Mode.REFERENCE;
        if (field ? !fieldName.matches("[a-z][a-zA-Z0-9_]{0,63}") : !fieldName.isEmpty())
            throw new IllegalArgumentException("配置检查须绑定字段，人工或未支持项不能伪装为字段检查");
        if ((mode == Mode.REFERENCE) != (reference != null)) throw new IllegalArgumentException("引用兑现项必须声明目标，其他兑现项不能携带引用目标");
        if (section == Section.RELATION && mode != Mode.UNSUPPORTED && mode != Mode.REFERENCE)
            throw new IllegalArgumentException("普通字段不能兑现对象关联，请使用单值引用或明确暂不支持");
    }
}
