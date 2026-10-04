package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.Optional;

/** Formula expressions contribute their persisted field dependencies to standard configuration governance. */
@Configuration
public class FormulaConfigurationReferences {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private final ObjectProvider<ModuleMetadataFormulaRuleService> rules;
    private final ObjectProvider<ModuleMetadataRelationService> relations;
    private final ConfigurationFieldPathReferenceResolver paths;
    private final FormulaEngine engine = new FormulaEngine();

    public FormulaConfigurationReferences(ObjectProvider<MetadataService> metadata,
            ObjectProvider<MetadataFieldService> fields, ObjectProvider<ModuleMetadataFieldService> moduleFields,
            ObjectProvider<ModuleMetadataRelationService> relations,
            ObjectProvider<MetadataFieldReferenceConfigService> referenceConfigs,
            ObjectProvider<ModuleMetadataFormulaRuleService> rules) {
        this.rules = rules;
        this.relations = relations;
        this.paths = new ConfigurationFieldPathReferenceResolver(metadata, fields, moduleFields, relations, referenceConfigs);
    }

    @Bean public ConfigurationReferenceContributor formulaMetadataFieldReference() {
        return contributor(ConfigurationReferenceTarget.METADATA_FIELD);
    }

    @Bean public ConfigurationReferenceContributor formulaModuleFieldReference() {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD);
    }

    @Bean public ConfigurationReferenceContributor formulaRelationPathReference() {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_RELATION);
    }

    private ConfigurationReferenceContributor contributor(ConfigurationReferenceTarget target) {
        return new ConfigurationReferenceContributor() {
            public ConfigurationReferenceTarget target() { return target; }
            public ConfigurationReference reference() {
                return new ConfigurationReference("formulaFieldPath", "公式字段依赖", "expression/targetField");
            }
            public boolean protectsFieldEvolution() { return true; }
            public String describeReference(String id) {
                return "公式“" + rules.getObject().select(id).getAlias() + "”的字段依赖";
            }
            public Optional<String> findReferenceId(String id) {
                var service = rules.getIfAvailable();
                if (service == null) return Optional.empty();
                for (var rule : service.list(Criteria.of(), ALL)) {
                    var relation = relations.getObject().select(rule.getRelationId());
                    if (relation == null) continue;
                    LinkedHashSet<String> fields;
                    try {
                        fields = new LinkedHashSet<>(engine.referencedFields(rule.getExpression()));
                    } catch (RuntimeException exception) {
                        throw new PlatformException("公式“" + rule.getAlias() + "”无法解析，请修复规则后再修改字段。", exception);
                    }
                    if (rule.getTargetField() != null && !rule.getTargetField().isBlank()) fields.add(rule.getTargetField());
                    for (String field : fields) {
                        if (paths.uses(relation, field,
                                target == ConfigurationReferenceTarget.METADATA_FIELD ? id : null,
                                target == ConfigurationReferenceTarget.MODULE_METADATA_FIELD ? id : null,
                                target == ConfigurationReferenceTarget.MODULE_METADATA_RELATION ? id : null)) {
                            return Optional.of(rule.getId());
                        }
                    }
                }
                return Optional.empty();
            }
        };
    }
}
