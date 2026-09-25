package net.ximatai.muyun.spring.platform.impact;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class RecordImpactRelationService extends AbstractAbilityService<RecordImpactRelation> implements
        SoftDeleteAbility<RecordImpactRelation> {
    public static final String MODULE_ALIAS = "platform.record_impact_relation";
    private static final PageRequest DEFAULT_QUERY_PAGE = PageRequest.of(1, 500);

    public RecordImpactRelationService(BaseDao<RecordImpactRelation, String> relationDao) {
        super(MODULE_ALIAS, RecordImpactRelation.class, relationDao);
    }

    public RecordImpactRelation registerFromOriginContext(RecordOriginContext context,
                                                          String targetRecordId,
                                                          String operatorUserId) {
        return registerFromOriginContext(context,
                context == null ? null : context.targetModuleAlias(),
                targetRecordId,
                operatorUserId);
    }

    public RecordImpactRelation registerFromOriginContext(RecordOriginContext context,
                                                          String targetModuleAlias,
                                                          String targetRecordId,
                                                          String operatorUserId) {
        if (context == null) {
            return null;
        }
        context.validateForTarget(targetModuleAlias);
        RecordImpactRelation relation = new RecordImpactRelation();
        relation.setImpactType(context.impactType());
        relation.setSourceModuleAlias(context.sourceModuleAlias());
        relation.setSourceRecordId(context.sourceRecordId());
        relation.setTargetModuleAlias(targetModuleAlias);
        relation.setTargetRecordId(targetRecordId);
        relation.setGenerationRuleId(context.generationRuleId());
        relation.setActionCode(context.actionCode());
        relation.setBatchId(context.batchId());
        relation.setDraftKey(context.draftKey());
        relation.setOperatorUserId(operatorUserId);
        insert(relation);
        return relation;
    }

    public List<RecordImpactRelation> listBySource(String sourceModuleAlias, String sourceRecordId) {
        return listBySource(sourceModuleAlias, sourceRecordId, DEFAULT_QUERY_PAGE);
    }

    public List<RecordImpactRelation> listBySource(String sourceModuleAlias,
                                                   String sourceRecordId,
                                                   PageRequest pageRequest) {
        return list(Criteria.of()
                        .eq("sourceModuleAlias", requireText(sourceModuleAlias, "sourceModuleAlias"))
                        .eq("sourceRecordId", requireText(sourceRecordId, "sourceRecordId")),
                pageOrDefault(pageRequest),
                Sort.desc("createdAt"));
    }

    public List<RecordImpactRelation> listByTarget(String targetModuleAlias, String targetRecordId) {
        return listByTarget(targetModuleAlias, targetRecordId, DEFAULT_QUERY_PAGE);
    }

    public List<RecordImpactRelation> listByTarget(String targetModuleAlias,
                                                   String targetRecordId,
                                                   PageRequest pageRequest) {
        return list(Criteria.of()
                        .eq("targetModuleAlias", requireText(targetModuleAlias, "targetModuleAlias"))
                        .eq("targetRecordId", requireText(targetRecordId, "targetRecordId")),
                pageOrDefault(pageRequest),
                Sort.desc("createdAt"));
    }

    public List<RecordImpactRelation> listByBatch(String batchId) {
        return listByBatch(batchId, DEFAULT_QUERY_PAGE);
    }

    public List<RecordImpactRelation> listByBatch(String batchId, PageRequest pageRequest) {
        return list(Criteria.of().eq("batchId", requireText(batchId, "batchId")),
                pageOrDefault(pageRequest),
                Sort.desc("createdAt"));
    }

    public List<RecordImpactRelation> listByGenerationRule(String generationRuleId) {
        return listByGenerationRule(generationRuleId, DEFAULT_QUERY_PAGE);
    }

    public List<RecordImpactRelation> listByGenerationRule(String generationRuleId, PageRequest pageRequest) {
        return list(Criteria.of().eq("generationRuleId", requireText(generationRuleId, "generationRuleId")),
                pageOrDefault(pageRequest),
                Sort.desc("createdAt"));
    }

    public List<RecordImpactRelation> listGeneratedTargets(String sourceModuleAlias,
                                                           String sourceRecordId,
                                                           String targetModuleAlias,
                                                           String generationRuleId,
                                                           PageRequest pageRequest) {
        Criteria criteria = Criteria.of()
                .eq("impactType", RecordImpactType.GENERATE_PUSH)
                .eq("sourceModuleAlias", requireText(sourceModuleAlias, "sourceModuleAlias"))
                .eq("sourceRecordId", requireText(sourceRecordId, "sourceRecordId"));
        if (targetModuleAlias != null && !targetModuleAlias.isBlank()) {
            criteria.eq("targetModuleAlias", targetModuleAlias.trim());
        }
        if (generationRuleId != null && !generationRuleId.isBlank()) {
            criteria.eq("generationRuleId", generationRuleId.trim());
        }
        return list(criteria, pageOrDefault(pageRequest), Sort.desc("createdAt"));
    }

    public boolean hasGeneratedTarget(String sourceModuleAlias,
                                      String sourceRecordId,
                                      String targetModuleAlias,
                                      String generationRuleId) {
        return !listGeneratedTargets(
                sourceModuleAlias,
                sourceRecordId,
                targetModuleAlias,
                generationRuleId,
                PageRequest.of(1, 1)
        ).isEmpty();
    }

    public List<String> findSourceRecordIdsForGeneratedTarget(String sourceModuleAlias,
                                                              String targetModuleAlias,
                                                              String targetRecordId,
                                                              String generationRuleId) {
        Criteria criteria = Criteria.of()
                .eq("impactType", RecordImpactType.GENERATE_PUSH)
                .eq("sourceModuleAlias", requireText(sourceModuleAlias, "sourceModuleAlias"))
                .eq("targetModuleAlias", requireText(targetModuleAlias, "targetModuleAlias"))
                .eq("targetRecordId", requireText(targetRecordId, "targetRecordId"));
        String normalizedGenerationRuleId = generationRuleId == null ? null : generationRuleId.trim();
        if (normalizedGenerationRuleId != null && !normalizedGenerationRuleId.isBlank()) {
            criteria.eq("generationRuleId", normalizedGenerationRuleId);
        }
        return list(criteria, DEFAULT_QUERY_PAGE, Sort.desc("createdAt")).stream()
                .map(RecordImpactRelation::getSourceRecordId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private PageRequest pageOrDefault(PageRequest pageRequest) {
        return pageRequest == null ? DEFAULT_QUERY_PAGE : pageRequest;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformException("Record impact relation " + fieldName + " must not be blank");
        }
        return value.trim();
    }
}
