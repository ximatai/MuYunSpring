package net.ximatai.muyun.spring.common.schema;

import java.util.List;
import java.util.stream.Stream;

/** Presentation eligibility is independent of platform storage ownership. */
public record PlatformFieldPolicy(String fieldName, String title, boolean composable,
                                  boolean readOnly, String referenceModuleAlias) {
    private static final List<PlatformFieldPolicy> AUDIT = List.of(
            new PlatformFieldPolicy("createdBy", "创建人", true, true, "iam.user"),
            new PlatformFieldPolicy("createdAt", "创建时间", true, true, null),
            new PlatformFieldPolicy("updatedBy", "更新人", true, true, "iam.user"),
            new PlatformFieldPolicy("updatedAt", "更新时间", true, true, null));
    private static final List<PlatformFieldPolicy> ALL = Stream.concat(
            StandardEntitySchema.fieldNames().stream(), PlatformDataScopeSchema.fieldNames().stream())
            .distinct().map(name -> AUDIT.stream().filter(field -> field.fieldName().equals(name))
                    .findFirst().orElseGet(() -> new PlatformFieldPolicy(name, name, false, true, null)))
            .toList();

    public static List<PlatformFieldPolicy> all() { return ALL; }
    public static List<PlatformFieldPolicy> auditFields() { return AUDIT; }
    public static PlatformFieldPolicy find(String name) {
        return ALL.stream().filter(field -> field.fieldName().equals(name)).findFirst().orElse(null);
    }
    public static boolean isAuditLabel(String name) {
        return AUDIT.stream().filter(field -> field.referenceModuleAlias() != null)
                .anyMatch(field -> (field.fieldName() + "Title").equals(name));
    }
    public static boolean isAudit(String name) {
        return AUDIT.stream().anyMatch(field -> field.fieldName().equals(name));
    }
}
