package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.reference.PlatformAuditReferences;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ordinary form round-trips may echo audit facts, but cannot change them. */
public final class PlatformAuditMutationGuard {
    private PlatformAuditMutationGuard() { }
    public static void validate(Map<String, ?> supplied, EntityContract existing) {
        Map<String, Object> persisted = existing == null ? Map.of() : PlatformAuditReferences.values(existing);
        for (var field : PlatformFieldPolicy.auditFields()) {
            Object value = supplied.get(field.fieldName());
            if (value == null) continue;
            Object expected = persisted.get(field.fieldName());
            if (expected instanceof java.time.Instant && value instanceof String text) {
                try { value = java.time.Instant.parse(text); } catch (java.time.format.DateTimeParseException ignored) { }
            }
            if (!Objects.equals(String.valueOf(value), expected == null ? null : String.valueOf(expected))) {
                throw new PlatformException("平台审计字段不可修改：" + field.title());
            }
        }
    }
    public static void validate(EntityContract supplied, EntityContract existing) {
        validate(PlatformAuditReferences.values(supplied), existing);
        PlatformPermissionMutationGuard.validate(supplied, existing);
        if (supplied instanceof net.ximatai.muyun.spring.common.model.standard.StandardEntity entity) {
            entity.setCreatedByTitle(null);
            entity.setUpdatedByTitle(null);
        }
        if (existing != null) {
            supplied.setCreatedBy(existing.getCreatedBy());
            supplied.setCreatedAt(existing.getCreatedAt());
        }
    }

    /**
     * Applies the same ordinary-form field protection to aggregate child rows.
     * Trusted service and background writes do not call this Web-boundary guard.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void validateAggregateChildren(EntityContract suppliedParent,
                                                 EntityContract existingParent,
                                                 Collection<? extends ChildRelation<?, ?>> relations) {
        if (suppliedParent == null || relations == null || relations.isEmpty()) {
            return;
        }
        for (ChildRelation relation : relations) {
            List<? extends EntityContract> incoming = relation.incomingChildren(suppliedParent);
            if (incoming == null || incoming.isEmpty()) {
                continue;
            }
            Map<String, EntityContract> persistedById = existingParent == null
                    ? Map.of()
                    : ((List<? extends EntityContract>) relation.selectChildren(existingParent.getId())).stream()
                    .filter(child -> child.getId() != null && !child.getId().isBlank())
                    .collect(java.util.stream.Collectors.toMap(EntityContract::getId, child -> child));
            for (EntityContract child : incoming) {
                String childId = child == null ? null : child.getId();
                validate(child, childId == null || childId.isBlank() ? null : persistedById.get(childId));
            }
        }
    }
}
