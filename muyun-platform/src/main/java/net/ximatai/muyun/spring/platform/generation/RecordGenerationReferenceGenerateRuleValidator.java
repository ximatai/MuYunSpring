package net.ximatai.muyun.spring.platform.generation;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldReferenceGenerateRuleValidator;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class RecordGenerationReferenceGenerateRuleValidator implements ModuleMetadataFieldReferenceGenerateRuleValidator {
    private final BaseDao<RecordGenerationRule, String> ruleDao;

    public RecordGenerationReferenceGenerateRuleValidator(BaseDao<RecordGenerationRule, String> ruleDao) {
        this.ruleDao = Objects.requireNonNull(ruleDao, "ruleDao must not be null");
    }

    @Override
    public void validateReferenceGenerateRule(String ruleId,
                                              String referenceModuleAlias,
                                              String ownerModuleAlias) {
        RecordGenerationRule rule = ruleId == null || ruleId.isBlank() ? null : ruleDao.findById(ruleId);
        if (rule == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-generation-reference-generate-rule-validator.referencegenerateruleid-requires-existing-generation-rule",
                    "referenceGenerateRuleId requires existing generation rule: {value1}"), Map.of("value1", String.valueOf(ruleId)));
        }
        String expectedSource = PlatformNameRules.requireModuleAlias(referenceModuleAlias);
        String expectedTarget = PlatformNameRules.requireModuleAlias(ownerModuleAlias);
        if (!Objects.equals(rule.getSourceModuleAlias(), expectedSource)
                || !Objects.equals(rule.getTargetModuleAlias(), expectedTarget)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-generation-reference-generate-rule-validator.referencegenerateruleid-requires-generation-rule-direction",
                    "referenceGenerateRuleId requires generation rule direction: {value1} -> {value2}"), Map.of("value1", String.valueOf(expectedSource), "value2", String.valueOf(expectedTarget)));
        }
    }
}
