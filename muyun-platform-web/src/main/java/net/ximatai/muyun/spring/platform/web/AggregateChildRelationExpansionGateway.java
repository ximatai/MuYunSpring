package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.web.WebListResponse;
import net.ximatai.muyun.spring.web.WebOutputSupport;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Reads a declared aggregate child relation for a list-row expansion.
 *
 * <p>The endpoint is deliberately narrower than a detail read: only a relation declared by the
 * list DSL can be fetched, and only its declared expansion fields leave the server.  The parent
 * is selected through the ordinary {@link PlatformAction#VIEW} data scope before the child query
 * is executed, so a list row cannot become a bypass around record-level authorization.</p>
 */
@Component
public class AggregateChildRelationExpansionGateway {
    private final ModuleExecutionPlanCatalog planCatalog;

    public AggregateChildRelationExpansionGateway(ModuleExecutionPlanCatalog planCatalog) {
        this.planCatalog = planCatalog;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public WebListResponse<java.util.Map<String, Object>> read(String moduleAlias,
                                                                 CrudAbility<?> parentService,
                                                                 String parentId,
                                                                 String relationCode) {
        ModuleExecutionPlan plan = planCatalog.find(moduleAlias)
                .orElseThrow(() -> new IllegalStateException(
                        "aggregate relation expansion requires compiled plan: " + moduleAlias));
        ResolvedPageListRelationExpansionDescriptor expansion = plan.uiDescriptor().page().list()
                .relationExpansions().stream()
                .filter(candidate -> candidate.relationCode().equals(relationCode))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "list relation expansion is not declared: " + relationCode));
        var relation = plan.uiDescriptor().detailRelations().stream()
                .filter(candidate -> candidate.code().equals(relationCode))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "unknown aggregate child relation: " + relationCode));
        if (relation.embeddedField() == null) {
            throw new IllegalStateException("list relation expansion requires an aggregate child relation: "
                    + relationCode);
        }
        EntityContract parent = selectVisibleParent(parentService, parentId);
        if (!(parentService instanceof ChildrenAbility<?> childrenAbility)) {
            throw new IllegalStateException("aggregate relation expansion requires ChildrenAbility: " + moduleAlias);
        }
        ChildRelation childRelation = ((List<ChildRelation>) (List<?>) childrenAbility.childRelations()).stream()
                .filter(candidate -> relationCode.equals(candidate.relationCode()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "aggregate child relation is not registered: " + moduleAlias + "." + relationCode));
        CrudAbility childService = childRelation.childAbility();
        List<EntityContract> children = (List<EntityContract>) childRelation.selectChildren(parent.getId());
        List<EntityContract> secured = WebOutputSupport.records(childService, children,
                net.ximatai.muyun.spring.common.security.FieldOutputContext.LIST);
        List<String> outputFields = expansionOutputFields(childService, expansion);
        RecordReadProjection projection = new RecordReadProjection(
                relation.targetModuleAlias(),
                "list_relation_expansion:" + relationCode,
                outputFields.stream().map(ViewFieldRef::main).toList(),
                List.of(), List.of());
        List<java.util.Map<String, Object>> projected = RecordReadProjectionProjector.project(secured, projection);
        return new WebListResponse<>(ReferenceReadProjectionPostProcessor.apply(
                childService.modelClass(), projected, outputFields));
    }

    /**
     * Reference labels are presentation companions of a declared expansion column, not additional
     * business columns.  Returning them keeps the narrow endpoint compatible with the standard
     * display renderer without making every list-expansion DSL repeat transient title-field names.
     */
    private static List<String> expansionOutputFields(CrudAbility<?> childService,
                                                      ResolvedPageListRelationExpansionDescriptor expansion) {
        LinkedHashSet<String> fields = new LinkedHashSet<>(expansion.fields());
        Class<?> modelClass = childService.modelClass();
        if (modelClass == null) {
            return List.copyOf(fields);
        }
        StaticReferenceResolver.plans(modelClass).forEach(plan -> {
            if (fields.contains(plan.sourceField())) {
                plan.projections().forEach(projection -> fields.add(projection.outputField()));
            }
        });
        StaticReferenceResolver.loadPaths(modelClass).forEach(path -> {
            if (fields.contains(path.sourceField())) {
                fields.add(path.outputField());
            }
        });
        return List.copyOf(fields);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EntityContract selectVisibleParent(CrudAbility<?> service, String parentId) {
        EntityContract parent = service instanceof DataScopeAbility<?> scoped
                ? (EntityContract) ((DataScopeAbility) scoped).selectForAction(PlatformAction.VIEW, parentId)
                : (EntityContract) service.select(parentId);
        if (parent == null) {
            throw new IllegalArgumentException("aggregate relation expansion parent is not visible: " + parentId);
        }
        return parent;
    }
}
