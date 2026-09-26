package net.ximatai.muyun.spring.platform.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Requirements-bound adapter over the standard metadata publisher; no separate DDL implementation. */
@Service
public class ApplicationConstructionFieldService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ApplicationConstructionPlanService plans;
    private final ApplicationConstructionFieldChangeDao receipts;
    private final MetadataService metadata;
    private final ModuleMetadataRelationService relations;
    private final MetadataFieldService fields;
    private final FieldSpecService specs;
    private final MetadataModelChangeSetPreviewService previews;
    private final MetadataModelChangeSetApplyService publisher;
    private final DynamicRuntimeActivationService activation;
    private final ActionExecutionPolicyService permissions;

    public ApplicationConstructionFieldService(ApplicationConstructionPlanService plans, ApplicationConstructionFieldChangeDao receipts,
            MetadataService metadata, ModuleMetadataRelationService relations, MetadataFieldService fields, FieldSpecService specs,
            MetadataModelChangeSetPreviewService previews, MetadataModelChangeSetApplyService publisher,
            DynamicRuntimeActivationService activation, ActionExecutionPolicyService permissions) {
        this.plans = Objects.requireNonNull(plans); this.receipts = Objects.requireNonNull(receipts);
        this.metadata = Objects.requireNonNull(metadata); this.relations = Objects.requireNonNull(relations); this.fields = Objects.requireNonNull(fields);
        this.specs = Objects.requireNonNull(specs); this.previews = Objects.requireNonNull(previews);
        this.publisher = Objects.requireNonNull(publisher); this.activation = Objects.requireNonNull(activation);
        this.permissions = Objects.requireNonNull(permissions);
    }
    public record Field(String name, String title, String specAlias, boolean required, boolean unique, boolean indexed) {
        public Field {
            if (name == null || !name.matches("[a-z][a-zA-Z0-9_]{0,63}")) throw new IllegalArgumentException("字段名称格式无效");
            if (title == null || title.isBlank() || title.length() > 120) throw new IllegalArgumentException("字段标题无效");
            if (specAlias == null || specAlias.isBlank() || specAlias.length() > 64) throw new IllegalArgumentException("字段规格无效");
        }
    }
    public record Proposal(int planRevision, String objectKey, Integer expectedMetadataVersion, List<Field> fields) {
        public Proposal {
            if (planRevision < 1 || expectedMetadataVersion == null || expectedMetadataVersion < 0)
                throw new IllegalArgumentException("请先读取已确认方案与当前字段状态");
            if (objectKey == null || !objectKey.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("业务对象标识无效");
            if (fields == null || fields.isEmpty() || fields.size() > 12 || fields.stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException("一次新增 1 至 12 个普通字段");
            fields = List.copyOf(fields);
            if (fields.stream().map(Field::name).distinct().count() != fields.size()) throw new IllegalArgumentException("字段名称不能重复");
        }
    }
    public record Spec(String alias, String title, String type, Integer length, Integer precision, Integer scale) {}
    public record Description(String moduleAlias, int planRevision, Integer metadataVersion, List<Field> fields, List<Spec> specs) {}
    public record Preview(Proposal proposal, String moduleAlias, List<MetadataChangeSetFieldImpact> fieldImpacts,
                          List<MetadataChangeSetSchemaImpact> schemaImpacts, List<MetadataChangeSetValidationIssue> warnings,
                          List<MetadataChangeSetValidationIssue> errors, String fingerprint) {}
    public record Command(String requestId, Proposal proposal, String fingerprint) {}
    public record Receipt(String requestId, String objectKey, int planRevision, String moduleAlias, List<Field> fields) {}
    public record Result(Receipt receipt, DynamicRuntimeActivationService.Status runtime) {}

    public Description describe(String planId, String objectKey) {
        requireOperator();
        var plan = plans.read(planId);
        var binding = binding(plan, objectKey);
        try (var ignored = TenantContext.system("construction field discovery")) {
            var entity = metadata.select(binding.metadataId());
            if (entity == null) throw new IllegalArgumentException("主实体已不可用，请检查建设状态");
            var actual = fields.list(Criteria.of().eq("metadataId", entity.getId()), new PageRequest(0, 256)).stream()
                    .map(field -> new Field(field.getFieldName(), field.getTitle(), field.getFieldSpecAlias(),
                            Boolean.TRUE.equals(field.getRequired()), Boolean.TRUE.equals(field.getUniqueField()), Boolean.TRUE.equals(field.getIndexed()))).toList();
            var catalog = specs.list(Criteria.of().eq("enabled", true), new PageRequest(0, 100)).stream()
                    .map(spec -> new Spec(spec.getAlias(), spec.getTitle(), spec.getFieldType().name(), spec.getDefaultLength(), spec.getDefaultPrecision(), spec.getDefaultScale())).toList();
            return new Description(binding.moduleAlias(), plan.revision(), entity.getVersion(), actual, catalog);
        }
    }
    public Preview preview(String planId, Proposal proposal) {
        requireOperator();
        var plan = plans.read(planId);
        if (proposal == null || proposal.planRevision() != plan.revision()) throw new IllegalArgumentException("需求版本已变化，请重新读取并预检");
        ApplicationConstructionRequirements.requireBuildable(plan.content(), proposal.objectKey());
        var binding = binding(plan, proposal.objectKey());
        try (var ignored = TenantContext.system("construction field preview")) {
            var preview = previews.preview(binding.moduleAlias(), changeSet(binding, proposal));
            return new Preview(proposal, binding.moduleAlias(), preview.fieldImpacts(), preview.schemaImpacts(),
                    preview.warnings(), preview.errors(), digest(json(List.of(planId, proposal, preview.proposalFingerprint()))));
        }
    }
    @Transactional
    public Result confirm(String planId, Command command) {
        requireOperator();
        plans.read(planId);
        if (command == null || command.proposal() == null || command.fingerprint() == null || !command.fingerprint().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("字段确认参数无效");
        requireRequestId(command.requestId());
        PlatformAbilityRuntime.lockMutationPartition("platform.application-construction-plan", planId);
        String id = digest(planId + ":" + command.requestId()).substring(0, 32);
        String fingerprint = digest(json(command));
        var previous = receipts.findById(id);
        if (previous != null) {
            if (!fingerprint.equals(previous.getRequestDigest())) throw new IllegalArgumentException("同一次确认内容已变化");
            return result(previous);
        }
        var checked = preview(planId, command.proposal());
        if (!checked.errors().isEmpty()) throw new IllegalArgumentException("字段预检未通过：" + checked.errors().stream().map(MetadataChangeSetValidationIssue::message).toList());
        if (!checked.fingerprint().equals(command.fingerprint())) throw new IllegalArgumentException("字段预检已过期，请重新审阅");
        var binding = binding(plans.read(planId), command.proposal().objectKey());
        try (var ignored = TenantContext.system("confirmed construction fields")) {
            var changeSet = changeSet(binding, command.proposal());
            var standardPreview = previews.preview(binding.moduleAlias(), changeSet);
            // Recheck the same frozen preview, never accept a newly generated publication token silently.
            if (!digest(json(List.of(planId, command.proposal(), standardPreview.proposalFingerprint()))).equals(command.fingerprint()))
                throw new IllegalArgumentException("字段基线已变化，请重新预检");
            publisher.apply(binding.moduleAlias(), new MetadataModelChangeSetApplyCommand(changeSet, standardPreview.proposalFingerprint()));
            var receipt = new ApplicationConstructionFieldChange();
            receipt.setId(id); receipt.setPlanId(planId); receipt.setPlanRevision(command.proposal().planRevision());
            receipt.setObjectKey(command.proposal().objectKey()); receipt.setRequestId(command.requestId());
            receipt.setRequestDigest(fingerprint); receipt.setModuleAlias(binding.moduleAlias());
            receipt.setFieldsJson(json(command.proposal().fields()));
            EntityLifecycle.prepareInsert(receipt, Instant.now()); receipts.insert(receipt);
            return new Result(receipt(receipt), null);
        }
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
    private ApplicationConstructionPlanService.Initialization binding(ApplicationConstructionPlanService.Snapshot plan, String objectKey) {
        if (plan.content().objects().stream().noneMatch(object -> object.key().equals(objectKey))) throw new IllegalArgumentException("对象不在当前确认方案中");
        var binding = plan.initializations().stream().filter(value -> value.objectKey().equals(objectKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请先初始化此业务对象"));
        try (var ignored = TenantContext.system("construction binding check")) {
            var relation = relations.select(binding.relationId());
            if (relation == null || relation.getRelationRole() != RelationRole.MAIN
                    || !Objects.equals(relation.getModuleAlias(), binding.moduleAlias())
                    || !Objects.equals(relation.getMetadataId(), binding.metadataId()))
                throw new IllegalArgumentException("已建对象绑定发生变化，请核对模块与主实体");
        }
        return binding;
    }
    private MetadataModelChangeSetPreviewCommand changeSet(ApplicationConstructionPlanService.Initialization binding, Proposal proposal) {
        var drafts = proposal.fields().stream().map(value -> {
            var field = new MetadataField(); field.setMetadataId(binding.metadataId()); field.setFieldName(value.name());
            field.setColumnName(value.name().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT));
            field.setTitle(value.title()); field.setFieldSpecAlias(value.specAlias());
            field.setRequired(value.required()); field.setUniqueField(value.unique()); field.setIndexed(value.indexed());
            return new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null, field);
        }).toList();
        return new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(binding.relationId(),
                proposal.expectedMetadataVersion(), Map.of(), drafts)), List.of(), List.of());
    }
    private void requireOperator() {
        if (!CurrentUserContext.currentUser().map(user -> user.system()).orElse(false)) throw new PlatformAccessDeniedException("字段建设目前要求系统配置身份");
        for (String action : List.of("previewMetadataModelChangeSet", "applyMetadataModelChangeSet"))
            permissions.requireAuthorized(ActionExecutionContext.ofActionCode(ModuleMetadataRelationService.MODULE_ALIAS, action, Set.of(), CurrentUserContext.currentUser()));
    }
    private static void requireRequestId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9-]{16,80}")) throw new IllegalArgumentException("确认标识格式无效");
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("字段参数无效", error); }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
