package net.ximatai.muyun.spring.platform.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Owns personal workspace requirements and revision history, never configuration publication. */
@Service
public class ApplicationConstructionPlanService {
    private final BaseDao<ApplicationConstructionPlan, String> plans;
    private final BaseDao<ApplicationConstructionPlanRevision, String> revisions;
    private final ApplicationConstructionInitializationDao initializations;
    private final ApplicationConstructionFieldChangeDao fieldChanges;
    private final ApplicationConstructionDeliveryDao deliveries;
    private static final ObjectMapper JSON = new ObjectMapper();

    public ApplicationConstructionPlanService(BaseDao<ApplicationConstructionPlan, String> plans,
            BaseDao<ApplicationConstructionPlanRevision, String> revisions, ApplicationConstructionInitializationDao initializations, ApplicationConstructionFieldChangeDao fieldChanges, ApplicationConstructionDeliveryDao deliveries) {
        this.plans = Objects.requireNonNull(plans);
        this.revisions = Objects.requireNonNull(revisions);
        this.initializations = Objects.requireNonNull(initializations);
        this.fieldChanges = Objects.requireNonNull(fieldChanges);
        this.deliveries = Objects.requireNonNull(deliveries);
    }

    public record ConfirmCommand(String requestId, int expectedRevision, ApplicationConstructionPlanContent content) {}
    public record Snapshot(String planId, int revision, ApplicationConstructionPlanContent content,
                           Instant confirmedAt, String constructionStatus, List<Initialization> initializations, List<ApplicationConstructionFieldService.Receipt> fieldChanges, List<ApplicationConstructionDeliveryService.Receipt> deliveries) {}
    public record Initialization(String objectKey, int planRevision, String moduleAlias, String metadataId, String relationId, String requestId) {}
    public static Initialization initialization(ApplicationConstructionInitialization receipt) {
        return new Initialization(receipt.getObjectKey(), receipt.getPlanRevision(), receipt.getModuleAlias(), receipt.getMetadataId(), receipt.getRelationId(), receipt.getRequestId());
    }
    public record Summary(String planId, String title, int revision, Instant updatedAt) {}

    public List<Summary> list() {
        Scope scope = scope();
        Criteria criteria = Criteria.of().eq("ownerId", scope.userId());
        if (scope.tenantId() == null) criteria.isNull("tenantId");
        else criteria.eq("tenantId", scope.tenantId());
        return plans.query(criteria, PageRequest.of(1, 30), Sort.desc("updatedAt")).stream()
                .map(plan -> new Summary(plan.getId(), plan.getTitle(), plan.getVersion() + 1, plan.getUpdatedAt())).toList();
    }

    public Snapshot read(String planId) {
        var plan = requirePlan(planId);
        return snapshot(planId, plan.getVersion() + 1, decode(plan.getContentJson()), plan.getUpdatedAt());
    }

    public List<Snapshot> history(String planId) {
        requirePlan(planId);
        return revisions.query(Criteria.of().eq("planId", planId), PageRequest.of(1, 30), Sort.desc("revisionNumber"))
                .stream().map(this::snapshot).toList();
    }

    /** This proves a particular confirmation committed, even if newer revisions exist. */
    public Snapshot confirmation(String planId, String requestId) {
        Scope scope = scope();
        requireId(planId);
        requireRequestId(requestId);
        var plan = plans.findById(planId);
        if (plan == null) return null;
        requireOwner(plan, scope);
        var revision = revisions.findById(revisionId(planId, requestId));
        return revision == null ? null : snapshot(revision);
    }

    @Transactional
    public Snapshot confirm(String planId, ConfirmCommand command) {
        requireId(planId);
        if (command == null || command.content() == null || command.expectedRevision() < 0)
            throw new IllegalArgumentException("方案内容与预期版本不能为空");
        requireRequestId(command.requestId());
        if (command.content().inScope().isEmpty() || command.content().acceptanceExamples().isEmpty())
            throw new IllegalArgumentException("确认方案前须明确本期范围和至少一个验收例子");
        Scope scope = scope();
        String json = encode(command.content());
        String digest = digest(command.expectedRevision() + ":" + json);
        PlatformAbilityRuntime.lockMutationPartition("platform.application-construction-plan", planId);
        var plan = plans.findById(planId);
        if (plan != null) requireOwner(plan, scope);
        var previous = revisions.findById(revisionId(planId, command.requestId()));
        if (previous != null) {
            if (plan == null || !digest.equals(previous.getRequestDigest()))
                throw new IllegalArgumentException("确认内容已变化，请重新审阅方案");
            return snapshot(previous);
        }
        int currentRevision = plan == null ? 0 : plan.getVersion() + 1;
        if (command.expectedRevision() != currentRevision)
            throw BusinessExceptions.warning("platform.construction-plan.stale", "方案已被修改，请读取最新版本后重新确认");
        if (plan != null && json.equals(plan.getContentJson()))
            throw new IllegalArgumentException("方案内容未变化，无需重复确认");
        Instant now = Instant.now();
        try (var ignored = scope.tenantId() == null ? TenantContext.system("personal construction plan") : TenantContext.use(scope.tenantId())) {
            if (plan == null) {
                plan = new ApplicationConstructionPlan();
                plan.setId(planId); plan.setOwnerId(scope.userId()); plan.setTenantId(scope.tenantId());
                plan.setTitle(command.content().title()); plan.setContentJson(json);
                EntityLifecycle.prepareInsert(plan, now);
                plans.insert(plan);
            } else {
                int expectedVersion = plan.getVersion();
                plan.setTitle(command.content().title()); plan.setContentJson(json);
                EntityLifecycle.prepareUpdate(plan, now);
                if (plans.updateByIdAndVersion(plan, expectedVersion) != 1)
                    throw new IllegalStateException("Construction plan changed while locked");
            }
            var revision = new ApplicationConstructionPlanRevision();
            revision.setId(revisionId(planId, command.requestId())); revision.setPlanId(planId);
            revision.setTenantId(scope.tenantId()); revision.setRevisionNumber(currentRevision + 1);
            revision.setRequestId(command.requestId()); revision.setRequestDigest(digest); revision.setContentJson(json);
            EntityLifecycle.prepareInsert(revision, now);
            revisions.insert(revision);
            return snapshot(revision);
        }
    }

    private ApplicationConstructionPlan requirePlan(String id) {
        requireId(id);
        var plan = plans.findById(id);
        if (plan == null) throw new PlatformAccessDeniedException("方案不存在或无权访问");
        requireOwner(plan, scope());
        return plan;
    }
    private void requireOwner(ApplicationConstructionPlan plan, Scope scope) {
        if (!Objects.equals(plan.getOwnerId(), scope.userId()) || !Objects.equals(plan.getTenantId(), scope.tenantId()))
            throw new PlatformAccessDeniedException("方案不存在或无权访问");
    }
    private Scope scope() {
        var user = CurrentUserContext.currentUser().orElseThrow(() -> new PlatformAccessDeniedException("请先登录"));
        if (!user.system() && (user.tenantId() == null || user.tenantId().isBlank()))
            throw new PlatformAccessDeniedException("租户身份不完整");
        return new Scope(user.userId(), user.system() ? null : user.tenantId());
    }
    private record Scope(String userId, String tenantId) {}
    private Snapshot snapshot(ApplicationConstructionPlanRevision revision) {
        return snapshot(revision.getPlanId(), revision.getRevisionNumber(), decode(revision.getContentJson()), revision.getCreatedAt());
    }
    private Snapshot snapshot(String planId, int revision, ApplicationConstructionPlanContent content, Instant confirmedAt) {
        var bindings = initializations.list(Criteria.of().eq("planId", planId)).stream().map(ApplicationConstructionPlanService::initialization).toList();
        return new Snapshot(planId, revision, content, confirmedAt, bindings.isEmpty() ? "NOT_STARTED" : "INITIALIZED", bindings, fieldChanges.list(Criteria.of().eq("planId", planId)).stream().map(ApplicationConstructionFieldService::receipt).toList(), deliveries.list(Criteria.of().eq("planId", planId)).stream().map(ApplicationConstructionDeliveryService::receipt).toList());
    }
    private static void requireId(String value) {
        if (value == null || !value.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("方案标识格式无效");
    }
    private static void requireRequestId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9-]{16,80}")) throw new IllegalArgumentException("确认标识格式无效");
    }
    private static String revisionId(String planId, String requestId) { return digest(planId + ":" + requestId).substring(0, 32); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static String encode(ApplicationConstructionPlanContent content) {
        try {
            String value = JSON.writeValueAsString(content);
            if (value.getBytes(StandardCharsets.UTF_8).length > 32 * 1024) throw new IllegalArgumentException("方案内容过长");
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalArgumentException("方案格式无效", exception); }
    }
    private static ApplicationConstructionPlanContent decode(String value) {
        try { return JSON.readValue(value, ApplicationConstructionPlanContent.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("保存的方案无法读取", exception); }
    }
}
