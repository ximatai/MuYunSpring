package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.common.option.OptionFieldDefinition;
import net.ximatai.muyun.spring.common.option.OptionFieldResolver;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Resolves only declared summary choices; it never reads business records. */
@Service
public class PageQuerySummaryCatalogService {
    private final StaticModuleDefinitionCatalog staticModules;
    private final ObjectProvider<DynamicRecordService> dynamicRecords;
    private final ListQuerySummaryContributorCatalog contributors;

    public PageQuerySummaryCatalogService(StaticModuleDefinitionCatalog staticModules,
                                          ObjectProvider<DynamicRecordService> dynamicRecords,
                                          ListQuerySummaryContributorCatalog contributors) {
        this.staticModules = staticModules;
        this.dynamicRecords = dynamicRecords;
        this.contributors = contributors;
    }

    public PageQuerySummaryCatalog list(String moduleAlias) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        Facts facts = staticModules.find(validAlias).map(this::staticFacts).orElseGet(() -> dynamicFacts(validAlias));
        List<net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition> fields = facts.fields();
        return new PageQuerySummaryCatalog(validAlias, ListQuerySummaryFieldCatalog.list(fields).stream()
                .map(field -> new PageQuerySummaryCatalog.Field(field.fieldName(), field.title())).toList(),
                ListQuerySummaryGroupFieldCatalog.list(fields, facts.options(), facts.references()).stream()
                        .map(field -> new PageQuerySummaryCatalog.GroupField(field.fieldName(), field.title(), field.kind())).toList(),
                contributors.list(validAlias).stream()
                        .map(item -> new PageQuerySummaryCatalog.Contributor(item.contributorKey(), item.title())).toList());
    }

    private Facts staticFacts(StaticModuleDefinition definition) {
        List<net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition> fields = definition.entities().isEmpty()
                ? List.of() : definition.entities().getFirst().fields();
        Map<String, OptionFieldDefinition> options = (definition.modelClass() == null ? List.<OptionFieldDefinition>of()
                : OptionFieldResolver.resolve(definition.modelClass())).stream()
                .collect(java.util.stream.Collectors.toMap(OptionFieldDefinition::fieldName, item -> item));
        Map<String, ReferencePlan> references = (definition.modelClass() == null ? List.<ReferencePlan>of()
                : StaticReferenceResolver.plans(definition.modelClass())).stream()
                .collect(java.util.stream.Collectors.toMap(ReferencePlan::sourceField, item -> item, (left, ignored) -> left));
        return new Facts(fields, options, references);
    }

    private Facts dynamicFacts(String moduleAlias) {
        DynamicRecordService records = dynamicRecords == null ? null : dynamicRecords.getIfAvailable();
        if (records == null) throw new IllegalArgumentException("module is unavailable: " + moduleAlias);
        var module = records.moduleDefinitions().stream().filter(item -> moduleAlias.equals(item.moduleAlias())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("module is unavailable: " + moduleAlias));
        List<net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition> fields = module.entities().stream()
                .filter(entity -> module.mainEntityAlias().equals(entity.alias())).findFirst().map(EntityDefinition::fields)
                .orElseThrow(() -> new IllegalArgumentException("module is unavailable: " + moduleAlias));
        Map<String, OptionFieldDefinition> options = fields.stream().filter(field -> field.dictionaryBinding() != null)
                .collect(java.util.stream.Collectors.toMap(net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition::fieldName,
                        field -> new OptionFieldDefinition(field.fieldName(), field.dictionaryBinding().toOptionBinding(),
                                field.dictionaryBinding().selectionMode())));
        Map<String, ReferencePlan> references = module.references().stream()
                .filter(reference -> module.mainEntityAlias().equals(reference.sourceEntityAlias()))
                .collect(java.util.stream.Collectors.toMap(reference -> reference.sourceField(), reference -> reference.plan(),
                        (left, ignored) -> left));
        return new Facts(fields, options, references);
    }

    private record Facts(List<net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition> fields,
                         Map<String, OptionFieldDefinition> options, Map<String, ReferencePlan> references) {}
}
