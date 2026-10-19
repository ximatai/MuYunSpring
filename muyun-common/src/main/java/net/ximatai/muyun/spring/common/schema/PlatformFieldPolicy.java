package net.ximatai.muyun.spring.common.schema;

import java.util.List;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.stream.Stream;

/** Presentation eligibility is independent of platform storage ownership. */
public record PlatformFieldPolicy(String fieldName, String title, boolean composable,
                                  boolean readOnly, String referenceModuleAlias) {
    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("id", "ID"), Map.entry("tenantId", "租户"), Map.entry("version", "版本号"),
            Map.entry("deleted", "已删除"), Map.entry("deletedAt", "删除时间"), Map.entry("deletedBy", "删除人"),
            Map.entry("createdBy", "创建人"), Map.entry("createdAt", "创建时间"),
            Map.entry("updatedBy", "更新人"), Map.entry("updatedAt", "更新时间"),
            Map.entry("authUserId", "归属用户"), Map.entry("authAssigneeIds", "负责人员"),
            Map.entry("authMemberIds", "参与人员"), Map.entry("authOrganizationId", "归属机构"),
            Map.entry("authDepartmentId", "归属部门"), Map.entry("authModuleAlias", "归属模块"),
            Map.entry("approvalInstanceId", "审批实例"), Map.entry("approvalStatus", "审批状态"),
            Map.entry("approvalSubmittedBy", "审批提交人"), Map.entry("approvalSubmittedAt", "审批提交时间"),
            Map.entry("approvalCompletedAt", "审批完成时间"));

    /** Shared labels do not change field composability or storage ownership. */
    public static TextReference titleSource(String fieldName) {
        String title = TITLES.get(fieldName);
        return title == null ? null : TextReference.code("query/field/" + fieldName + "/title", title);
    }

    private static final List<PlatformFieldPolicy> AUDIT = List.of(
            new PlatformFieldPolicy("createdBy", TITLES.get("createdBy"), true, true, "iam.user"),
            new PlatformFieldPolicy("createdAt", TITLES.get("createdAt"), true, true, null),
            new PlatformFieldPolicy("updatedBy", TITLES.get("updatedBy"), true, true, "iam.user"),
            new PlatformFieldPolicy("updatedAt", TITLES.get("updatedAt"), true, true, null));
    private static final List<PlatformFieldPolicy> ALL = Stream.concat(
            StandardEntitySchema.fieldNames().stream(), PlatformDataScopeSchema.fieldNames().stream())
            .distinct().map(name -> AUDIT.stream().filter(field -> field.fieldName().equals(name))
                    .findFirst().orElseGet(() -> new PlatformFieldPolicy(name, TITLES.getOrDefault(name, name), false, true, null)))
            .toList();

    public net.ximatai.muyun.spring.common.i18n.TextReference titleSource() {
        return net.ximatai.muyun.spring.common.i18n.TextReference.code("query/field/" + fieldName + "/title", title);
    }

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
