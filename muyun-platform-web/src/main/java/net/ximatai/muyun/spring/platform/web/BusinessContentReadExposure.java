package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import java.util.*;
import java.util.stream.Collectors;

/** One compiled field-exposure contract for synchronous content reads and deferred generation. */
final class BusinessContentReadExposure {
    private BusinessContentReadExposure() { }

    static Set<String> fields(ModuleExecutionPlan plan, String relationCode) {
        if (relationCode == null) return mainFields(plan);
        var relation = plan.uiDescriptor().detailRelations().stream().filter(value -> relationCode.equals(value.code())
                        && plan.moduleAlias().equals(value.targetModuleAlias()))
                .findFirst().orElseThrow(() -> new PlatformAccessDeniedException("Business content relation is unavailable"));
        if (relation.visible() != null && Boolean.FALSE.equals(relation.visible().constant())) return Set.of();
        var projection = relation.listProjection() != null ? relation.listProjection()
                : relation.queryContract() == null ? null : relation.queryContract().listProjection();
        if (projection != null) return projection.fields().stream()
                .map(net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationListField::fieldName)
                .collect(Collectors.toUnmodifiableSet());
        return plan.readModel().fields().stream().filter(field -> relation.targetEntityAlias().equals(field.entityAlias()))
                .map(ResolvedModuleReadField::fieldName).collect(Collectors.toUnmodifiableSet());
    }
    private static Set<String> mainFields(ModuleExecutionPlan plan) {
        Set<String> readable = plan.readModel().fields().stream()
                .filter(field -> field.relationCode() == null && Objects.equals(field.entityAlias(), plan.readModel().mainEntityAlias()))
                .map(ResolvedModuleReadField::fieldName).collect(Collectors.toSet());
        var ui = plan.uiDescriptor(); var page = ui.page();
        List<ResolvedViewDescriptor> views = new ArrayList<>();
        if (ui.defaultEditor() != null) views.add(ui.defaultEditor());
        ui.editorSurfaces().forEach(surface -> views.add(surface.editor()));
        ui.editorContributions().forEach(contribution -> views.add(contribution.editor()));
        if (page != null) {
            if (page.list() != null) views.add(page.list().fields());
            if (page.detail() != null) {
                if (page.detail().display() != null) views.add(page.detail().display());
                if (page.detail().editor() != null) views.add(page.detail().editor());
            }
        }
        // API-only modules have no presentation restriction. A published empty/hidden page
        // must never fall back to the static model's complete storage field catalogue.
        if (page == null && views.isEmpty()) return Set.copyOf(readable);
        Set<String> exposed = new HashSet<>();
        for (var view : views) if (view != null) for (var field : view.fields()) {
            if (field.fieldRef().relationCode() == null
                    && (field.visible() == null || !Boolean.FALSE.equals(field.visible().constant())))
                exposed.add(field.fieldRef().fieldName());
        }
        if (page != null && page.explorer() != null) {
            if (page.explorer().titleField() != null) exposed.add(page.explorer().titleField());
            if (page.explorer().secondaryField() != null) exposed.add(page.explorer().secondaryField());
        }
        exposed.retainAll(readable);
        return Set.copyOf(exposed);
    }
}
