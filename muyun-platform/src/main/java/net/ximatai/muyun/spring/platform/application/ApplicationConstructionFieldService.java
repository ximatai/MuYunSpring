package net.ximatai.muyun.spring.platform.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Read projection of current metadata and historical field receipts for optional construction plans. */
@Service
public class ApplicationConstructionFieldService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ApplicationConstructionPlanService plans;
    private final ApplicationConstructionFieldChangeDao receipts;
    private final MetadataService metadata;
    private final ModuleMetadataFormulaRuleService formulaRules;
    private final ReferenceTargetFieldCatalogService targets;
    private final ModuleMetadataFieldPropertySummaryService properties;
    private final ModuleMetadataRelationService relations;
    private final MetadataFieldService fields;
    private final FieldSpecService specs;
    private final DynamicRuntimeActivationService activation;
    private final ActionExecutionPolicyService permissions;

    public ApplicationConstructionFieldService(ApplicationConstructionPlanService plans, ApplicationConstructionFieldChangeDao receipts,
            MetadataService metadata, ModuleMetadataRelationService relations, MetadataFieldService fields, FieldSpecService specs,
            DynamicRuntimeActivationService activation, ActionExecutionPolicyService permissions,
            ReferenceTargetFieldCatalogService targets, ModuleMetadataFieldPropertySummaryService properties,
            ModuleMetadataFormulaRuleService formulaRules) {
        this.formulaRules = Objects.requireNonNull(formulaRules);
        this.targets = Objects.requireNonNull(targets); this.properties = Objects.requireNonNull(properties);
        this.plans = Objects.requireNonNull(plans); this.receipts = Objects.requireNonNull(receipts);
        this.metadata = Objects.requireNonNull(metadata); this.relations = Objects.requireNonNull(relations); this.fields = Objects.requireNonNull(fields);
        this.specs = Objects.requireNonNull(specs); this.activation = Objects.requireNonNull(activation);
        this.permissions = Objects.requireNonNull(permissions);
    }
    /** Immutable historical payload; not a current field creation command. */
    public record Field(String name, String title, String specAlias, boolean required, boolean unique,
                        boolean indexed, MetadataFieldReferenceConfigDraft reference, boolean titleField) {}
    public record Spec(String alias, String title, String type, Integer length, Integer precision, Integer scale) {}
    public record Child(ModuleMetadataRelation relation, Integer metadataVersion, List<MetadataField> fields,
                        Map<String, MetadataFieldReferenceConfigDraft> references) {}
    public record Description(String moduleAlias, int planRevision, Integer metadataVersion, List<MetadataField> fields, List<Spec> specs,
                              Map<String, MetadataFieldReferenceConfigDraft> references, Map<String, Child> children,
                              List<ModuleMetadataFormulaRule> calculationRules) {}
    public record Receipt(String requestId, String objectKey, int planRevision, String moduleAlias, List<Field> fields) {}
    public record Result(Receipt receipt, DynamicRuntimeActivationService.Status runtime) {}

    public Description describe(String planId, String objectKey) {
        requireOperator();
        var plan = plans.read(planId);
        var binding = binding(plan, objectKey);
        try (var ignored = TenantContext.system("construction field discovery")) {
            var entity = metadata.select(binding.metadataId());
            if (entity == null) throw new IllegalArgumentException("主实体已不可用，请检查建设状态");
            var actual = fields.list(Criteria.of().eq("metadataId", entity.getId()), new PageRequest(0, Integer.MAX_VALUE));
            var catalog = specs.list(Criteria.of().eq("enabled", true), new PageRequest(0, Integer.MAX_VALUE)).stream()
                    .map(spec -> new Spec(spec.getAlias(), spec.getTitle(), spec.getFieldType().name(), spec.getDefaultLength(), spec.getDefaultPrecision(), spec.getDefaultScale())).toList();
            var children = new TreeMap<String, Child>();
            for (var relation : relations.list(Criteria.of().eq("moduleAlias", binding.moduleAlias())
                    .eq("relationRole", RelationRole.CHILD).eq("parentMetadataId", binding.metadataId()), new PageRequest(0, Integer.MAX_VALUE))) {
                var child = metadata.select(relation.getMetadataId());
                if (child == null) continue;
                var childFields = fields.list(Criteria.of().eq("metadataId", child.getId()), new PageRequest(0, Integer.MAX_VALUE));
                if (childFields.stream().noneMatch(field -> Objects.equals(field.getFieldName(), relation.getForeignKey())
                        && field.getFieldForm() == MetadataFieldForm.PHYSICAL && !Boolean.FALSE.equals(field.getEnabled()))) continue;
                children.put(relation.getRelationAlias(), new Child(relation, child.getVersion(), childFields,
                        references(binding.moduleAlias(), relation.getId())));
            }
            var calculations = formulaRules.list(Criteria.of().eq("relationId", binding.relationId())
                    .eq("enabled", true).eq("ruleKind", net.ximatai.muyun.spring.common.formula.FormulaRuleKind.CALCULATION)
                    .eq("rulePhase", net.ximatai.muyun.spring.common.formula.FormulaRulePhase.BEFORE_SAVE), new PageRequest(0, Integer.MAX_VALUE));
            return new Description(binding.moduleAlias(), plan.revision(), entity.getVersion(), actual, catalog,
                    references(binding.moduleAlias(), binding.relationId()), Collections.unmodifiableMap(children), calculations);
        }
    }
    private Map<String, MetadataFieldReferenceConfigDraft> references(String moduleAlias, String relationId) {
        Map<String, MetadataFieldReferenceConfigDraft> result = new TreeMap<>();
        for (var property : properties.list(moduleAlias, relationId)) {
            var config = property.reference();
            if (property.kind() == MetadataFieldPropertyKind.MODULE_REFERENCE && config != null && config.cardinality() == ReferenceCardinality.ONE) {
                try {
                    var target = targets.list(moduleAlias, relationId, config.targetModuleAlias(), config.targetMetadataId());
                    if (target.keyFields().stream().anyMatch(key -> key.fieldName().equals(config.targetKeyField()))
                            && target.labelFields().stream().anyMatch(label -> label.fieldName().equals(config.targetLabelField())))
                        result.put(property.fieldName(), new MetadataFieldReferenceConfigDraft(config.targetModuleAlias(),
                                config.targetMetadataId(), config.targetKeyField(), config.targetLabelField(), config.cardinality(),
                                config.targetUnavailablePolicy(), config.projectionMappings(), config.requireEnabled(), config.affectMappings()));
                } catch (PlatformException unavailable) {
                    // An unavailable target is missing evidence, never a fulfilled relationship.
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public Result status(String planId, String requestId) {
        requireOperator(); plans.read(planId); requireRequestId(requestId);
        var receipt = receipts.findById(digest(planId + ":" + requestId).substring(0, 32));
        return receipt == null ? null : result(receipt);
    }
    private Result result(ApplicationConstructionFieldChange receipt) {
        try (var ignored = TenantContext.system("construction field result")) {
            return new Result(receipt(receipt), activation.status(receipt.getModuleAlias()));
        }
    }
    public static Receipt receipt(ApplicationConstructionFieldChange value) {
        try {
            return new Receipt(value.getRequestId(), value.getObjectKey(), value.getPlanRevision(), value.getModuleAlias(),
                    List.of(JSON.readValue(value.getFieldsJson(), Field[].class)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("字段回执无法读取", error); }
    }
    public record Binding(String objectKey, String moduleAlias, String metadataId, String relationId) {}
    /** Resolve current platform configuration, never reuse historical metadata/relation identities. */
    Binding binding(ApplicationConstructionPlanService.Snapshot plan, String objectKey) {
        var link = plan.moduleBindings().stream().filter(value -> value.objectKey().equals(objectKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请在方案中关联已发现的标准模块；关联不创建或发布配置"));
        try (var ignored = TenantContext.system("construction current binding")) {
            var current = relations.list(Criteria.of().eq("moduleAlias", link.moduleAlias()).eq("relationRole", RelationRole.MAIN), PageRequest.of(1, 2));
            if (current.size() != 1 || metadata.select(current.getFirst().getMetadataId()) == null)
                throw new IllegalArgumentException("关联模块尚无可用主实体，请通过标准元数据治理核对配置");
            var relation = current.getFirst();
            return new Binding(objectKey, link.moduleAlias(), relation.getMetadataId(), relation.getId());
        }
    }
    public MetadataCapabilityCatalog.DesignContract designContract() {
        requireOperator();
        return MetadataCapabilityCatalog.designContract();
    }
    public List<ReferenceTargetFieldCatalogService.ModuleCandidate> businessObjects() {
        requireOperator();
        try (var ignored = TenantContext.system("construction reference discovery")) { return targets.discoverModules(); }
    }
    public ReferenceTargetFieldCatalog referenceTarget(String moduleAlias) {
        requireOperator();
        try (var ignored = TenantContext.system("construction reference discovery")) { return targets.target(moduleAlias, null); }
    }
    public List<ApplicationConstructionRequirements.Evidence> evidence(ApplicationConstructionPlanService.Snapshot plan,
            String objectKey, Description description) {
        var objectModules = new HashMap<String, String>();
        plan.moduleBindings().forEach(binding -> objectModules.put(binding.objectKey(), binding.moduleAlias()));
        var referenceTargets = new HashMap<String, String>();
        description.references().forEach((field, reference) -> referenceTargets.put(field, reference.targetModuleAlias()));
        var childFields = new HashMap<String, MetadataField>();
        description.children().forEach((alias, child) -> {
            child.fields().forEach(field -> childFields.put(alias + "." + field.getFieldName(), field));
            child.references().forEach((name, reference) -> referenceTargets.put(alias + "." + name, reference.targetModuleAlias()));
        });
        var calculationTargets = new HashSet<String>();
        var engine = new net.ximatai.muyun.spring.common.formula.FormulaEngine();
        for (var rule : description.calculationRules()) {
            calculationTargets.addAll(engine.assignedFields(rule.getExpression()));
            if (rule.getTargetField() != null) calculationTargets.add(rule.getTargetField());
        }
        return ApplicationConstructionRequirements.evaluate(plan.content(), objectKey, description.fields(), referenceTargets,
                objectModules, childFields, description.children().keySet(), calculationTargets);
    }
    private void requireOperator() {
        if (!CurrentUserContext.currentUser().map(user -> user.system()).orElse(false)) throw new PlatformAccessDeniedException("字段建设目前要求系统配置身份");
        for (String action : List.of("previewMetadataModelChangeSet", "applyMetadataModelChangeSet"))
            permissions.requireAuthorized(ActionExecutionContext.ofActionCode(ModuleMetadataRelationService.MODULE_ALIAS, action, Set.of(), CurrentUserContext.currentUser()));
    }
    private static void requireRequestId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9-]{16,80}")) throw new IllegalArgumentException("确认标识格式无效");
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
