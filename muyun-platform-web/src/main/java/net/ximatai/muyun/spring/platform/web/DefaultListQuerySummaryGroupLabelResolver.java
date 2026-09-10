package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.PageRequests;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.OptionFieldResolver;
import net.ximatai.muyun.spring.common.option.OptionQuery;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Batch-only group label resolver. Reference labels deliberately use REFERENCE option reads;
 * {@code ReferenceAbility.titles()} would read through an unscoped DAO path.
 */
@Service
public class DefaultListQuerySummaryGroupLabelResolver implements ListQuerySummaryGroupLabelResolver {
    private final StaticModuleDefinitionCatalog staticModules;
    private final DynamicRecordService dynamicRecords;
    private final OptionSourceRegistry options;

    public DefaultListQuerySummaryGroupLabelResolver(StaticModuleDefinitionCatalog staticModules,
                                                      ObjectProvider<DynamicRecordService> dynamicRecords,
                                                      OptionSourceRegistry options) {
        this.staticModules = staticModules;
        this.dynamicRecords = dynamicRecords == null ? null : dynamicRecords.getIfAvailable();
        this.options = options;
    }

    @Override
    public Map<String, String> labels(String moduleAlias, ResolvedPageListQuerySummaryDescriptor summary,
                                      Collection<Object> values) {
        if (values == null || values.isEmpty()) return Map.of();
        OptionBinding option = optionBinding(moduleAlias, summary.groupByField()).orElse(null);
        if (option != null) {
            return options.source(option).options(OptionQuery.all()).stream()
                    .collect(java.util.stream.Collectors.toMap(item -> item.code(), item -> item.title(),
                            (left, ignored) -> left, java.util.LinkedHashMap::new));
        }
        ReferencePlan plan = referencePlan(moduleAlias, summary.groupByField()).orElse(null);
        if (plan == null) return Map.of();
        List<String> ids = values.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)).stream().toList();
        if (ids.isEmpty()) return Map.of();
        var target = PlatformAbilityRuntime.referenceTargetResolver().resolve(plan.target())
                .or(() -> dynamicRecords == null ? Optional.empty() : dynamicRecords.referenceAbility(plan.target()))
                .orElseThrow(() -> new IllegalArgumentException("group reference target is unavailable: "
                        + plan.target().qualifiedName()));
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        target.referenceOptions(plan, Criteria.of().in(StandardEntitySchema.ID_FIELD, ids), PageRequests.all())
                .getRecords().forEach(optionItem -> {
                    if (optionItem.id() != null && optionItem.title() != null && !optionItem.title().isBlank()) {
                        labels.putIfAbsent(optionItem.id(), optionItem.title());
                    }
                });
        return Map.copyOf(labels);
    }

    private Optional<OptionBinding> optionBinding(String moduleAlias, String fieldName) {
        var staticDefinition = staticModules.find(moduleAlias).orElse(null);
        if (staticDefinition != null) {
            if (staticDefinition.modelClass() == null) return Optional.empty();
            return OptionFieldResolver.resolve(staticDefinition.modelClass()).stream()
                    .filter(field -> field.fieldName().equals(fieldName)).findFirst().map(field -> field.binding());
        }
        if (dynamicRecords == null) return Optional.empty();
        var dynamic = dynamicRecords.describe(moduleAlias);
        return dynamic.entities().stream()
                .filter(entity -> entity.entityAlias().equals(dynamic.mainEntityAlias()))
                .flatMap(entity -> entity.fields().stream()).filter(field -> field.fieldName().equals(fieldName))
                .map(field -> field.optionBinding()).filter(java.util.Objects::nonNull).findFirst();
    }

    private Optional<ReferencePlan> referencePlan(String moduleAlias, String fieldName) {
        var staticDefinition = staticModules.find(moduleAlias).orElse(null);
        if (staticDefinition != null) {
            if (staticDefinition.modelClass() == null) return Optional.empty();
            return StaticReferenceResolver.plans(staticDefinition.modelClass()).stream()
                    .filter(plan -> plan.sourceField().equals(fieldName)).findFirst();
        }
        if (dynamicRecords == null) return Optional.empty();
        return dynamicRecords.moduleDefinitions().stream().filter(module -> module.moduleAlias().equals(moduleAlias))
                .flatMap(module -> module.references().stream()
                        .filter(reference -> module.mainEntityAlias().equals(reference.sourceEntityAlias()))
                        .filter(reference -> fieldName.equals(reference.sourceField())).map(reference -> reference.plan()))
                .findFirst();
    }
}
