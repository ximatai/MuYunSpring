package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.platform.code.CodeRuleService;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationSplitGroupFieldService;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationSplitPolicyService;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationFieldMappingService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigFieldService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Cross-domain registry for structured configuration references.
 *
 * <p>It keeps deletion governance at the metadata integration boundary while each contributor
 * still queries the service that owns the persisted reference fact. Object providers avoid
 * turning this registry into a startup dependency cycle.</p>
 */
@Configuration
class ConfigurationReferenceContributorConfiguration {
    @Bean ConfigurationReferenceContributor metadataFieldReference(ObjectProvider<MetadataFieldService> service) {
        return contributor(ConfigurationReferenceTarget.METADATA, "metadataField", "字段", "metadataId", service);
    }
    @Bean ConfigurationReferenceContributor moduleRelationMetadataReference(ObjectProvider<ModuleMetadataRelationService> service) {
        return contributor(ConfigurationReferenceTarget.METADATA, "moduleMetadataRelation", "模块元数据关系", "metadataId", service);
    }
    @Bean ConfigurationReferenceContributor moduleRelationParentMetadataReference(ObjectProvider<ModuleMetadataRelationService> service) {
        return contributor(ConfigurationReferenceTarget.METADATA, "moduleMetadataRelationParent", "模块元数据关系", "parentMetadataId", service);
    }
    @Bean ConfigurationReferenceContributor fieldConfigReference(ObjectProvider<MetadataFieldConfigService> service) {
        return contributor(ConfigurationReferenceTarget.METADATA_FIELD, "fieldConfig", "字段配置", "metadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor fieldProtectionReference(ObjectProvider<MetadataFieldProtectionConfigService> service) {
        return contributor(ConfigurationReferenceTarget.METADATA_FIELD, "fieldProtection", "字段保护配置", "metadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor fieldReferenceReference(ObjectProvider<MetadataFieldReferenceConfigService> service) {
        return contributor(ConfigurationReferenceTarget.METADATA_FIELD, "fieldReference", "字段引用配置", "metadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor fieldReferenceAffectReference(ObjectProvider<MetadataFieldReferenceConfigService> service) {
        return new ConfigurationReferenceContributor() {
            @Override public ConfigurationReferenceTarget target() { return ConfigurationReferenceTarget.METADATA_FIELD; }
            @Override public ConfigurationReference reference() {
                return new ConfigurationReference("fieldReferenceAffect", "引用选择回填配置", "affectMappings");
            }
            @Override public Optional<String> findReferenceId(String targetId) {
                var references = service.getIfAvailable();
                return references == null ? Optional.empty() : references.findAffectReferenceId(targetId);
            }
        };
    }
    @Bean ConfigurationReferenceContributor fieldReferenceTargetMetadataReference(ObjectProvider<MetadataFieldReferenceConfigService> service) {
        return contributor(ConfigurationReferenceTarget.METADATA, "fieldReferenceTargetMetadata", "字段引用配置", "targetMetadataId", service);
    }
    @Bean ConfigurationReferenceContributor viewFieldReference(ObjectProvider<MetadataViewFieldService> service) {
        return contributor(ConfigurationReferenceTarget.METADATA_FIELD, "viewField", "视图字段", "metadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor moduleFieldMetadataFieldReference(ObjectProvider<ModuleMetadataFieldService> service) {
        return contributor(ConfigurationReferenceTarget.METADATA_FIELD, "moduleField", "模块字段", "metadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor companionFieldReference(ObjectProvider<MetadataFieldService> service) {
        return contributor(ConfigurationReferenceTarget.METADATA_FIELD, "companionField", "伴生或影子字段", "ownerFieldId", service);
    }
    @Bean ConfigurationReferenceContributor moduleFieldFilterReference(ObjectProvider<ModuleMetadataFieldFilterService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldFilter", "字段过滤规则", "moduleMetadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor moduleFieldAffectReference(ObjectProvider<ModuleMetadataFieldAffectService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldAffect", "字段影响规则", "moduleMetadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor uiConfigFieldReference(ObjectProvider<PlatformUiConfigFieldService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "uiConfigField", "页面字段", "moduleMetadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor queryItemReference(ObjectProvider<PlatformQueryItemService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "queryItem", "查询项", "moduleMetadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor codeRuleReference(ObjectProvider<CodeRuleService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "codeRule", "编码规则", "moduleMetadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor codeRuleMetadataFieldReference(ObjectProvider<CodeRuleService> service) {
        return contributor(ConfigurationReferenceTarget.METADATA_FIELD, "codeRuleMetadataField", "编码规则", "metadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor splitGroupFieldReference(ObjectProvider<RecordGenerationSplitGroupFieldService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "generationSplitGroupField", "生单拆分字段", "moduleMetadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor splitPolicyReference(ObjectProvider<RecordGenerationSplitPolicyService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "generationSplitPolicy", "生单拆分策略", "quantityModuleMetadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor generationSourceFieldReference(ObjectProvider<RecordGenerationFieldMappingService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "generationSourceFieldMapping", "生单源字段映射", "sourceModuleMetadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor generationTargetFieldReference(ObjectProvider<RecordGenerationFieldMappingService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "generationTargetFieldMapping", "生单目标字段映射", "targetModuleMetadataFieldId", service);
    }
    @Bean ConfigurationReferenceContributor filterFormFieldReference(ObjectProvider<ModuleMetadataFieldFilterService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldFilterFormField", "字段过滤规则", "formFieldId", service);
    }
    @Bean ConfigurationReferenceContributor filterReferenceFieldReference(ObjectProvider<ModuleMetadataFieldFilterService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldFilterReferenceField", "字段过滤规则", "referenceFieldId", service);
    }
    @Bean ConfigurationReferenceContributor affectReferenceFieldReference(ObjectProvider<ModuleMetadataFieldAffectService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldAffectReferenceField", "字段影响规则", "referenceFieldId", service);
    }
    @Bean ConfigurationReferenceContributor affectTargetFieldReference(ObjectProvider<ModuleMetadataFieldAffectService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldAffectTargetField", "字段影响规则", "targetFieldId", service);
    }
    @Bean ConfigurationReferenceContributor unitFieldReference(ObjectProvider<ModuleMetadataFieldService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldUnitField", "模块字段单位配置", "unitFieldId", service);
    }
    @Bean ConfigurationReferenceContributor baseValueFieldReference(ObjectProvider<ModuleMetadataFieldService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldBaseValueField", "模块字段单位配置", "baseValueFieldId", service);
    }
    @Bean ConfigurationReferenceContributor conversionScopeFieldReference(ObjectProvider<ModuleMetadataFieldService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldConversionScopeField", "模块字段单位配置", "conversionScopeFieldId", service);
    }
    @Bean ConfigurationReferenceContributor moneyCurrencyFieldReference(ObjectProvider<ModuleMetadataFieldService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldMoneyCurrencyField", "模块字段金额配置", "moneyCurrencyFieldId", service);
    }
    @Bean ConfigurationReferenceContributor moneyBaseAmountFieldReference(ObjectProvider<ModuleMetadataFieldService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldMoneyBaseAmountField", "模块字段金额配置", "moneyBaseAmountFieldId", service);
    }
    @Bean ConfigurationReferenceContributor moneyRateDateFieldReference(ObjectProvider<ModuleMetadataFieldService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldMoneyRateDateField", "模块字段金额配置", "moneyRateDateFieldId", service);
    }
    @Bean ConfigurationReferenceContributor moneyExchangeRateFieldReference(ObjectProvider<ModuleMetadataFieldService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, "moduleFieldMoneyExchangeRateField", "模块字段金额配置", "moneyExchangeRateFieldId", service);
    }
    @Bean ConfigurationReferenceContributor relationModuleFieldReference(ObjectProvider<ModuleMetadataFieldService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_RELATION, "moduleField", "模块字段", "relationId", service);
    }
    @Bean ConfigurationReferenceContributor relationViewReference(ObjectProvider<MetadataViewService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_RELATION, "metadataView", "元数据视图", "relationId", service);
    }
    @Bean ConfigurationReferenceContributor relationFieldConfigReference(ObjectProvider<MetadataFieldConfigService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_RELATION, "fieldConfig", "字段配置", "relationId", service);
    }
    @Bean ConfigurationReferenceContributor relationFieldReference(ObjectProvider<MetadataFieldReferenceConfigService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_RELATION, "fieldReference", "字段引用配置", "relationId", service);
    }
    @Bean ConfigurationReferenceContributor relationFormulaReference(ObjectProvider<ModuleMetadataFormulaRuleService> service) {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_RELATION, "formulaRule", "公式规则", "relationId", service);
    }

    private static ConfigurationReferenceContributor contributor(ConfigurationReferenceTarget target, String resourceKey,
                                                                   String resourceName, String field,
                                                                   ObjectProvider<? extends CrudAbility<? extends EntityContract>> serviceProvider) {
        ConfigurationReference reference = new ConfigurationReference(resourceKey, resourceName, field);
        return new ConfigurationReferenceContributor() {
            @Override public ConfigurationReferenceTarget target() { return target; }
            @Override public ConfigurationReference reference() { return reference; }
            @Override public Optional<String> findReferenceId(String targetId) {
                CrudAbility<? extends EntityContract> service = serviceProvider.getIfAvailable();
                if (service == null) return Optional.empty();
                return service.list(Criteria.of().eq(field, targetId), PageRequest.of(1, 1)).stream()
                        .findFirst().map(EntityContract::getId);
            }
        };
    }
}
