package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import java.util.*;
import java.util.stream.Collectors;

/** One compiled field-exposure contract for synchronous content reads and deferred generation. */
final class BusinessContentReadExposure {
    private BusinessContentReadExposure() { }

    static Set<String> fields(ModuleExecutionPlan plan, String relationCode) {
        if (relationCode == null) return plan.readModel().fields().stream()
                .filter(field -> field.relationCode() == null && Objects.equals(field.entityAlias(), plan.readModel().mainEntityAlias()))
                .map(ResolvedModuleReadField::fieldName).collect(Collectors.toUnmodifiableSet());
        var relation = plan.uiDescriptor().detailRelations().stream().filter(value -> relationCode.equals(value.code())
                        && plan.moduleAlias().equals(value.targetModuleAlias()))
                .findFirst().orElseThrow(() -> new PlatformAccessDeniedException("Business content relation is unavailable"));
        var projection = relation.listProjection() != null ? relation.listProjection()
                : relation.queryContract() == null ? null : relation.queryContract().listProjection();
        if (projection != null) return projection.fields().stream()
                .map(net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationListField::fieldName)
                .collect(Collectors.toUnmodifiableSet());
        return plan.readModel().fields().stream().filter(field -> relation.targetEntityAlias().equals(field.entityAlias()))
                .map(ResolvedModuleReadField::fieldName).collect(Collectors.toUnmodifiableSet());
    }
}
