package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityAssociationViewDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public record DynamicEntityDescriptor(
        String entityAlias,
        String title,
        Set<String> capabilities,
        List<String> sortPartitionFields,
        List<DynamicFieldDescriptor> fields,
        List<DynamicFormulaRuleDescriptor> formulaRules,
        List<DynamicActionDescriptor> actions,
        List<DynamicViewDescriptor> views,
        List<DynamicAssociationViewDescriptor> associationViews,
        List<DynamicFileReferenceDescriptor> fileReferences
) {
    public DynamicEntityDescriptor {
        sortPartitionFields = sortPartitionFields == null ? List.of() : List.copyOf(sortPartitionFields);
        fields = fields == null ? List.of() : List.copyOf(fields);
        formulaRules = formulaRules == null ? List.of() : List.copyOf(formulaRules);
        actions = actions == null ? List.of() : List.copyOf(actions);
        views = views == null ? List.of() : List.copyOf(views);
        associationViews = associationViews == null ? List.of() : List.copyOf(associationViews);
        fileReferences = fileReferences == null ? List.of() : List.copyOf(fileReferences);
    }

    /** Source-compatible constructor for descriptors before file-reference field facts existed. */
    public DynamicEntityDescriptor(String entityAlias, String title, Set<String> capabilities,
                                   List<DynamicFieldDescriptor> fields, List<DynamicFormulaRuleDescriptor> formulaRules,
                                   List<DynamicActionDescriptor> actions, List<DynamicViewDescriptor> views,
                                   List<DynamicAssociationViewDescriptor> associationViews) {
        this(entityAlias, title, capabilities, List.of(), fields, formulaRules, actions, views, associationViews, List.of());
    }

    /** Source-compatible constructor for descriptors before file-reference and sort-partition facts existed. */
    public DynamicEntityDescriptor(String entityAlias, String title, Set<String> capabilities,
                                   List<DynamicFieldDescriptor> fields, List<DynamicFormulaRuleDescriptor> formulaRules,
                                   List<DynamicActionDescriptor> actions, List<DynamicViewDescriptor> views,
                                   List<DynamicAssociationViewDescriptor> associationViews,
                                   List<DynamicFileReferenceDescriptor> fileReferences) {
        this(entityAlias, title, capabilities, List.of(), fields, formulaRules, actions, views, associationViews,
                fileReferences);
    }

    public static DynamicEntityDescriptor from(EntityDefinition entity) {
        return from(entity, List.of(), List.of());
    }

    public static DynamicEntityDescriptor from(EntityDefinition entity, List<EntityViewDefinition> views) {
        return from(entity, views, List.of());
    }

    public static DynamicEntityDescriptor from(EntityDefinition entity,
                                               List<EntityViewDefinition> views,
                                               List<EntityActionDefinition> actions) {
        return from(null, entity, views, List.of(), actions);
    }

    public static DynamicEntityDescriptor from(String moduleAlias,
                                               EntityDefinition entity,
                                               List<EntityViewDefinition> views,
                                               List<EntityAssociationViewDefinition> associationViews,
                                               List<EntityActionDefinition> actions) {
        return from(moduleAlias, entity, List.of(), views, associationViews, actions);
    }

    public static DynamicEntityDescriptor from(String moduleAlias,
                                               EntityDefinition entity,
                                               List<EntityReferenceDefinition> references,
                                               List<EntityViewDefinition> views,
                                               List<EntityAssociationViewDefinition> associationViews,
                                               List<EntityActionDefinition> actions) {
        Map<String, DynamicReferenceDescriptor> referencesByField = references == null
                ? Map.of()
                : references.stream()
                .filter(reference -> entity.alias().equals(reference.sourceEntityAlias()))
                .map(DynamicReferenceDescriptor::from)
                .collect(Collectors.toMap(DynamicReferenceDescriptor::sourceField, Function.identity()));
        return new DynamicEntityDescriptor(
                entity.alias(),
                entity.name(),
                entity.capabilities().stream()
                        .map(EntityCapability::name)
                        .collect(Collectors.toUnmodifiableSet()),
                entity.sortPartitionFields(),
                entity.fields().stream()
                        .map(field -> DynamicFieldDescriptor.from(field, referencesByField.get(field.fieldName())))
                        .toList(),
                entity.orderedFormulaRules().stream()
                        .map(DynamicFormulaRuleDescriptor::from)
                        .toList(),
                DynamicStandardActions.from(moduleAlias, entity, actions),
                DynamicViewDescriptors.from(entity, views),
                scopedAssociationViews(entity, associationViews),
                entity.fileReferences().entrySet().stream()
                        .map(entry -> DynamicFileReferenceDescriptor.from(entry.getKey(), entry.getValue()))
                        .toList()
        );
    }

    private static List<DynamicAssociationViewDescriptor> scopedAssociationViews(EntityDefinition entity,
                                                                                List<EntityAssociationViewDefinition> views) {
        return views == null ? List.of() : views.stream()
                .filter(view -> entity.alias().equals(view.sourceEntityAlias()))
                .map(DynamicAssociationViewDescriptor::from)
                .toList();
    }
}
