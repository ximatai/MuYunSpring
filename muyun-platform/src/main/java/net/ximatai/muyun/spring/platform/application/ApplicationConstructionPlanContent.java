package net.ximatai.muyun.spring.platform.application;

import java.util.List;
import java.util.HashSet;

/** Business requirements only; never a second executable module/schema definition. */
public record ApplicationConstructionPlanContent(
        String title, String goal, List<String> inScope, List<String> outOfScope,
        List<BusinessObject> objects, List<String> relationships, List<String> rules,
        List<String> questions, List<String> assumptions, List<Decision> decisions,
        List<String> acceptanceExamples, List<ApplicationConstructionRequirement> requirements) {
    public ApplicationConstructionPlanContent {
        title = text(title, 120); goal = text(goal, 1500);
        inScope = texts(inScope); outOfScope = texts(outOfScope);
        relationships = texts(relationships); rules = texts(rules);
        questions = texts(questions); assumptions = texts(assumptions);
        acceptanceExamples = texts(acceptanceExamples);
        objects = items(objects); decisions = items(decisions);
        // Older saved plans remain readable, but missing mappings block construction/acceptance.
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        if (requirements.size() > 64) throw new IllegalArgumentException("需求兑现项最多 64 项");
        var keys = new HashSet<String>();
        for (BusinessObject object : objects) {
            if (!keys.add(object.key())) throw new IllegalArgumentException("业务对象标识不能重复");
        }
        var bindings = new HashSet<String>();
        for (var requirement : requirements) {
            var source = switch (requirement.section()) { case SCOPE -> inScope; case RULE -> rules; case RELATION -> relationships; };
            if (requirement.index() >= source.size() || !keys.contains(requirement.objectKey()))
                throw new IllegalArgumentException("需求兑现项必须引用本版要求和业务对象");
            if (!bindings.add(requirement.section() + ":" + requirement.index() + ":" + requirement.objectKey() + ":" + requirement.mode() + ":" + requirement.fieldName()))
                throw new IllegalArgumentException("需求兑现项不能重复");
        }
    }
    public record BusinessObject(String key, String name, String purpose) {
        public BusinessObject {
            key = text(key, 64); name = text(name, 120); purpose = text(purpose, 500);
            if (!key.matches("[a-z][a-z0-9_-]*")) throw new IllegalArgumentException("业务对象标识格式无效");
        }
    }
    public record Decision(String statement, Source source) {
        public Decision {
            statement = text(statement, 500);
            if (source == null) throw new IllegalArgumentException("需求来源不能为空");
        }
    }
    public enum Source { USER_REQUIREMENT, RECOMMENDATION }
    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException("方案文本为空或超出长度限制");
        return value.trim();
    }
    private static <T> List<T> items(List<T> values) {
        if (values == null || values.size() > 16 || values.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("每组方案条目最多 16 项，不能包含空项");
        return List.copyOf(values);
    }
    private static List<String> texts(List<String> values) {
        return items(values).stream().map(value -> text(value, 500)).toList();
    }
}
