package net.ximatai.muyun.spring.platform.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.ModuleMainMetadataCreateCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A governed configuration node, not a second model publisher or workflow engine. */
@Service
public class ApplicationConstructionInitializationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ApplicationConstructionPlanService plans;
    private final ApplicationConstructionInitializationDao receipts;
    private final ApplicationService applications;
    private final PlatformModuleService modules;
    private final ModuleMetadataOrchestrationService orchestration;
    private final MetadataService metadata;
    private final DynamicRuntimeActivationService activation;
    private final ActionExecutionPolicyService permissions;
    private final IDatabaseOperations<?> database;

    public ApplicationConstructionInitializationService(ApplicationConstructionPlanService plans,
            ApplicationConstructionInitializationDao receipts, ApplicationService applications,
            PlatformModuleService modules, ModuleMetadataOrchestrationService orchestration, MetadataService metadata,
            DynamicRuntimeActivationService activation, ActionExecutionPolicyService permissions, IDatabaseOperations<?> database) {
        this.plans = Objects.requireNonNull(plans);
        this.receipts = Objects.requireNonNull(receipts);
        this.applications = Objects.requireNonNull(applications);
        this.modules = Objects.requireNonNull(modules);
        this.orchestration = Objects.requireNonNull(orchestration);
        this.metadata = Objects.requireNonNull(metadata);
        this.activation = Objects.requireNonNull(activation);
        this.permissions = Objects.requireNonNull(permissions);
        this.database = Objects.requireNonNull(database);
    }

    public record Proposal(int planRevision, String objectKey, String applicationAlias, String applicationTitle,
                           String moduleName) {
        public Proposal {
            if (planRevision < 1) throw new IllegalArgumentException("请先确认需求方案");
            if (objectKey == null || !objectKey.matches("[a-z][a-z0-9_-]{0,63}"))
                throw new IllegalArgumentException("业务对象标识无效");
            applicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
            if (applicationAlias.length() > 32) throw new IllegalArgumentException("应用标识不能超过 32 字符");
            moduleName = PlatformNameRules.requireIdentifier(moduleName, "moduleName");
            PlatformNameRules.requireModuleAlias(applicationAlias + "." + moduleName);
            if (applicationAlias.equals("platform") || applicationAlias.equals("iam"))
                throw new IllegalArgumentException("请使用业务应用，不能写入平台内置应用");
            if (applicationTitle == null || applicationTitle.isBlank() || applicationTitle.length() > 120)
                throw new IllegalArgumentException("应用名称不能为空或超过 120 字符");
            applicationTitle = applicationTitle.trim();
        }
    }
    public record Preview(Proposal proposal, String applicationTitle, boolean createsApplication,
                          Integer applicationVersion, String moduleAlias, String moduleTitle,
                          String schemaName, String tableName, List<String> remainingWork, String fingerprint) {}
    public record ConfirmCommand(String requestId, Proposal proposal, String fingerprint) {}
    public record Result(ApplicationConstructionPlanService.Initialization receipt,
                         DynamicRuntimeActivationService.Status runtime) {}

    public Preview preview(String planId, Proposal proposal) {
        requireOperator();
        var plan = plans.read(planId);
        if (proposal == null || plan.revision() != proposal.planRevision())
            throw new IllegalArgumentException("需求方案版本已变化，请重新读取并预检");
        ApplicationConstructionRequirements.requireBuildable(plan.content(), proposal.objectKey());
        var object = plan.content().objects().stream().filter(value -> value.key().equals(proposal.objectKey()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("业务对象不在已确认方案中"));
        if (receipts.findById(receiptId(planId, proposal.objectKey())) != null)
            throw new IllegalArgumentException("此业务对象已初始化，请读取建设进度后继续编排");
        try (var ignored = TenantContext.system("construction initialization preview")) {
            var application = applications.select(proposal.applicationAlias());
            if (application != null && (!Boolean.TRUE.equals(application.getEnabled()) || Boolean.TRUE.equals(application.getSystemManaged())))
                throw new IllegalArgumentException("目标应用不可用于本次建设");
            if (application == null) requireAction(ApplicationService.MODULE_ALIAS, PlatformAction.CREATE.code());
            String moduleAlias = proposal.applicationAlias() + "." + proposal.moduleName();
            // DAO identity checks include soft-deleted objects; never reuse an archived identity.
            if (modules.getDao().findById(moduleAlias) != null)
                throw new IllegalArgumentException("模块标识已被使用，请重新选择");
            if (application == null && applications.getDao().findById(proposal.applicationAlias()) != null)
                throw new IllegalArgumentException("应用标识已被归档，请重新选择");
            if (metadata.getDao().count(Criteria.of().eq("applicationAlias", proposal.applicationAlias()).eq("alias", proposal.moduleName())) > 0)
                throw new IllegalArgumentException("主实体标识已被使用，请重新选择");
            String table = "app_" + digest(planId + ":" + proposal.objectKey() + ":" + moduleAlias).substring(0, 32);
            if (tableExists(table)) throw new IllegalArgumentException("目标物理表已存在，禁止接管存量数据表");
            Integer version = application == null ? null : application.getVersion();
            String title = application == null ? proposal.applicationTitle() : application.getTitle();
            String fingerprint = digest(json(List.of(planId, proposal, version == null ? -1 : version, title, object.name(), table)));
            return new Preview(proposal, title, application == null, version, moduleAlias, object.name(), "public", table,
                    List.of("业务字段与关系尚未编排", "页面与菜单尚未发布", "尚未执行方案中的业务规则与验收例子"), fingerprint);
        }
    }

    @Transactional
    public Result confirm(String planId, ConfirmCommand command) {
        requireOperator();
        plans.read(planId); // ownership is checked even for an idempotent retry
        if (command == null || command.proposal() == null || command.requestId() == null
                || !command.requestId().matches("[a-zA-Z0-9-]{16,80}")
                || command.fingerprint() == null || !command.fingerprint().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("初始化确认参数无效");
        var proposal = command.proposal();
        // Same lock as requirements confirmation: no plan edit may race a configuration commit.
        PlatformAbilityRuntime.lockMutationPartition("platform.application-construction-plan", planId);
        String id = receiptId(planId, proposal.objectKey());
        String requestDigest = digest(json(command));
        var previous = receipts.findById(id);
        if (previous != null) {
            if (!previous.getRequestId().equals(command.requestId()) || !previous.getRequestDigest().equals(requestDigest))
                throw new IllegalArgumentException("此对象已初始化，不得重复创建；请读取建设进度");
            return result(previous);
        }
        // Serialize competing initializations into the same application, including creation of the application itself.
        PlatformAbilityRuntime.lockMutationPartition("platform.construction-application", proposal.applicationAlias());
        database.query("select id from platform_application where id = ? for update", proposal.applicationAlias());
        Preview preview = preview(planId, proposal);
        if (!preview.fingerprint().equals(command.fingerprint()))
            throw new IllegalArgumentException("初始化预检已过期，请重新审阅");
        try (var ignored = TenantContext.system("confirmed construction initialization")) {
            if (preview.createsApplication()) {
                var application = new Application();
                application.setAlias(proposal.applicationAlias());
                application.setTitle(preview.applicationTitle());
                applications.insert(application);
            }
            var module = new PlatformModule();
            module.setAlias(preview.moduleAlias());
            module.setApplicationAlias(proposal.applicationAlias());
            module.setModuleKind(ModuleKind.DYNAMIC);
            module.setTitle(preview.moduleTitle());
            modules.insert(module);
            var created = orchestration.createMainMetadata(preview.moduleAlias(), new ModuleMainMetadataCreateCommand(
                    proposal.moduleName(), preview.moduleTitle(), preview.schemaName(), preview.tableName(), false));
            var receipt = new ApplicationConstructionInitialization();
            receipt.setId(id); receipt.setPlanId(planId); receipt.setPlanRevision(proposal.planRevision());
            receipt.setObjectKey(proposal.objectKey()); receipt.setRequestId(command.requestId());
            receipt.setRequestDigest(requestDigest); receipt.setModuleAlias(preview.moduleAlias());
            receipt.setMetadataId(created.metadata().getId()); receipt.setRelationId(created.relation().getId());
            EntityLifecycle.prepareInsert(receipt, Instant.now());
            receipts.insert(receipt);
            // Runtime activation is post-commit; the response must not claim it is already active.
            return new Result(ApplicationConstructionPlanService.initialization(receipt), null);
        }
    }

    public Result status(String planId, String objectKey) {
        requireOperator();
        plans.read(planId);
        var receipt = receipts.findById(receiptId(planId, objectKey));
        return receipt == null ? null : result(receipt);
    }
    private Result result(ApplicationConstructionInitialization receipt) {
        try (var ignored = TenantContext.system("construction initialization result")) {
            return new Result(ApplicationConstructionPlanService.initialization(receipt), activation.status(receipt.getModuleAlias()));
        }
    }
    private void requireOperator() {
        var user = CurrentUserContext.currentUser().orElseThrow(() -> new PlatformAccessDeniedException("请先登录"));
        if (!user.system()) throw new PlatformAccessDeniedException("模块初始化目前要求系统配置身份");
        requireAction(PlatformModuleService.MODULE_ALIAS, PlatformAction.CREATE.code());
        requireAction(ModuleMetadataRelationService.MODULE_ALIAS, "createMainMetadata");
    }
    private void requireAction(String module, String action) {
        permissions.requireAuthorized(ActionExecutionContext.ofActionCode(module, action, Set.of(), CurrentUserContext.currentUser()));
    }
    private boolean tableExists(String table) {
        var rows = database.query("select to_regclass(?::text) as relation", "public." + table);
        return !rows.isEmpty() && rows.getFirst().get("relation") != null;
    }

    private static String receiptId(String planId, String objectKey) { return digest(planId + ":" + objectKey).substring(0, 32); }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("初始化参数无效", error); }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
