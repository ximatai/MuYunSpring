package net.ximatai.muyun.spring.platform.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Computes evidence from confirmed requirements and actual configuration, never model completion claims. */
public final class ApplicationConstructionRequirements {
    private ApplicationConstructionRequirements() {}
    public enum Status { UNMAPPED, UNSUPPORTED, CONFIGURATION_MISSING, CONFIGURATION_MATCHED, MANUAL_CHECK_REQUIRED }
    public record Evidence(ApplicationConstructionRequirement.Section section, int index, String statement,
                           String objectKey, String fieldName, Status status, String explanation) {}

    public static List<String> source(ApplicationConstructionPlanContent content, ApplicationConstructionRequirement.Section section) {
        return switch (section) { case SCOPE -> content.inScope(); case RULE -> content.rules(); case RELATION -> content.relationships(); };
    }
    public static List<Evidence> evaluate(ApplicationConstructionPlanContent content, String objectKey,
                                          List<ApplicationConstructionFieldService.Field> fields) {
        Map<String, ApplicationConstructionFieldService.Field> byName = fields.stream()
                .collect(Collectors.toMap(ApplicationConstructionFieldService.Field::name, field -> field));
        var result = new ArrayList<Evidence>();
        for (var section : ApplicationConstructionRequirement.Section.values()) {
            var source = source(content, section);
            for (int i = 0; i < source.size(); i++) {
                int index = i;
                var bindings = content.requirements().stream().filter(item -> item.section() == section && item.index() == index).toList();
                if (bindings.isEmpty()) {
                    result.add(new Evidence(section, i, source.get(i), objectKey, "", Status.UNMAPPED, "尚未确认如何兑现；不能视为已实现"));
                    continue;
                }
                for (var binding : bindings) {
                    if (!binding.objectKey().equals(objectKey)) continue;
                    var field = byName.get(binding.fieldName());
                    var status = switch (binding.mode()) {
                        case UNSUPPORTED -> Status.UNSUPPORTED;
                        case MANUAL -> Status.MANUAL_CHECK_REQUIRED;
                        case FIELD -> field != null ? Status.CONFIGURATION_MATCHED : Status.CONFIGURATION_MISSING;
                        case REQUIRED -> field != null && field.required() ? Status.CONFIGURATION_MATCHED : Status.CONFIGURATION_MISSING;
                        case UNIQUE -> field != null && field.unique() ? Status.CONFIGURATION_MATCHED : Status.CONFIGURATION_MISSING;
                    };
                    result.add(new Evidence(section, i, source.get(i), objectKey, binding.fieldName(), status, binding.explanation()));
                }
            }
        }
        return List.copyOf(result);
    }
    public static boolean blocked(List<Evidence> evidence) {
        return evidence.stream().anyMatch(item -> item.status() == Status.UNMAPPED || item.status() == Status.UNSUPPORTED);
    }
    public static boolean missingConfiguration(List<Evidence> evidence) {
        return evidence.stream().anyMatch(item -> item.status() == Status.CONFIGURATION_MISSING);
    }
    public static void requireBuildable(ApplicationConstructionPlanContent content, String objectKey) {
        if (blocked(evaluate(content, objectKey, List.of())))
            throw new IllegalArgumentException("本期要求存在未对应或不支持项，请先商定并确认分期范围及逐项兑现方式");
    }
}
