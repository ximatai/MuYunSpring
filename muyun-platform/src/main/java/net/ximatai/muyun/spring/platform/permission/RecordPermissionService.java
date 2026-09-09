package net.ximatai.muyun.spring.platform.permission;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.action.DataChange;
import net.ximatai.muyun.spring.ability.action.MutationContextHolder;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.ability.permission.RecordPermissionChange;
import net.ximatai.muyun.spring.ability.permission.RecordPermissionAccess;
import net.ximatai.muyun.spring.ability.permission.RecordPermissionChanges;
import net.ximatai.muyun.spring.ability.permission.RecordPermissionPersistence;
import net.ximatai.muyun.spring.ability.permission.RecordPermissionState;
import net.ximatai.muyun.spring.ability.permission.RecordPermissionWrite;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.capability.DataScopeCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeFieldMapping;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared record authorization relations; entry adapters only resolve the module service. */
@Service
public class RecordPermissionService {
    private final RuntimeEventPublisher events;
    private final ActionExecutionPolicyService authorization;
    public RecordPermissionService(ActionExecutionPolicyService authorization, RuntimeEventPublisher events) {
        this.events = events;
        this.authorization = authorization;
    }

    private <T extends EntityContract> RecordPermissionAccess<T> requireRecord(CrudAbility<T> service, String id) {
        boolean enabled = service instanceof RecordPermissionPersistence<?> source
                ? source.supportsRecordPermissions() : service instanceof DataScopeAbility<?>;
        if (!enabled) throw new PlatformException("数据权限能力未启用");
        authorization.requireRecordAction(ActionExecutionContext.ofPlatformAction(service.getModuleAlias(),
                PlatformAction.MANAGE_PERMISSIONS, Set.of(id), CurrentUserContext.currentUser()));
        DataScopeFieldMapping mapping = service instanceof DataScopeAbility<?> scoped
                ? scoped.dataScopeFieldMapping() : DataScopeFieldMapping.STANDARD;
        if (!DataScopeFieldMapping.STANDARD.equals(mapping)) throw new PlatformException("业务归属映射不支持通用权限变更");
        RecordPermissionAccess<T> access = selectForPermissionAction(service, id);
        T record = access.record();
        if (record == null) throw new PlatformException("记录不存在或无权限管理");
        if (!(record instanceof DataScopeCapable)) throw new PlatformException("记录未启用数据权限能力");
        return access;
    }

    @SuppressWarnings("unchecked")
    private <T extends EntityContract> RecordPermissionAccess<T> selectForPermissionAction(CrudAbility<T> service,
                                                                                            String id) {
        if (service instanceof DataScopeAbility<?> scoped) {
            DataScopeAbility<T> dataScoped = DataScopeAbility.cast(scoped);
            DataScopeCriteriaResult scope = dataScoped.readScope(PlatformAction.MANAGE_PERMISSIONS,
                    Criteria.of().eq("id", id));
            return new RecordPermissionAccess<>(dataScoped.selectForAction(PlatformAction.MANAGE_PERMISSIONS, id),
                    scope.crossTenant());
        }
        if (service instanceof RecordPermissionPersistence<?> source) {
            return ((RecordPermissionPersistence<T>) source).readForPermissionAction(id);
        }
        return new RecordPermissionAccess<>(service.select(id), false);
    }

    public <T extends EntityContract> RecordPermissionState read(CrudAbility<T> service, String id) {
        RecordPermissionAccess<T> access = requireRecord(service, id);
        return withTenantScope(access, () -> state(access.record()));
    }

    public <T extends EntityContract> List<ReferenceOption> candidates(CrudAbility<T> service, String id, String keyword) {
        RecordPermissionAccess<T> access = requireRecord(service, id);
        T record = access.record();
        Criteria criteria = candidateCriteria(record);
        if (keyword != null && !keyword.isBlank()) criteria.like("title", "%" + keyword.trim() + "%");
        return users().referenceOptions(criteria, new PageRequest(0, 50)).getRecords();
    }

    @Transactional
    public <T extends EntityContract> void change(CrudAbility<T> service, String id, RecordPermissionChange command) {
        RecordPermissionAccess<T> access = requireRecord(service, id);
        T record = access.record();
        Map<String, Object> before = relations(record);
        validateTargetUsers(record, command, access);
        RecordPermissionChanges.apply(record, command);
        try (var context = ActionExecutionContextHolder.use(ActionExecutionContext.ofPlatformAction(service.getModuleAlias(),
                PlatformAction.MANAGE_PERMISSIONS, Set.of(id), CurrentUserContext.currentUser()))) {
            int count;
            if (service instanceof RecordPermissionPersistence writer) {
                count = writer.updatePermissions(RecordPermissionWrite.from(record));
            }
            else count = service.update(record);
            if (count != 1) throw new OptimisticLockException("权限变更未保存，请刷新后重试");
        }

        events.publishAfterCommit(new RuntimeEvent(null, null, RuntimeEventType.ACTION_EXECUTED, service.getModuleAlias(),
                service.getModuleAlias(), id, PlatformAction.MANAGE_PERMISSIONS.code(), record.getTenantId(),
                false, CurrentUserContext.currentUser().map(user -> user.userId()).orElse(null), "USER", "ALLOWED",
                service.getModuleAlias() + "." + PlatformAction.MANAGE_PERMISSIONS.code(), PlatformAction.MANAGE_PERMISSIONS.code(),
                RuntimeMutationSource.ACTION, Map.of("operation", command.operation().name(),
                        "before", before, "after", relations(record)), null));
        MutationContextHolder.current().ifPresent(context -> context.record(DataChange.recordUpdated(service.getModuleAlias(), id)));
    }

    private void validateTargetUsers(EntityContract record,
                                     RecordPermissionChange command,
                                     RecordPermissionAccess<?> access) {
        if (command == null || command.operation() == RecordPermissionChange.Operation.REMOVE) {
            return;
        }

        List<String> ids = command.userIds();
        if (ids.isEmpty()) {
            throw new PlatformException("目标人员不可用或不在可选范围内");
        }

        Criteria criteria = candidateCriteria(record).in("id", ids);
        long availableCount = users().referenceOptions(criteria, new PageRequest(0, ids.size()))
                .getRecords().stream()
                .map(ReferenceOption::id)
                .distinct()
                .count();
        if (availableCount != new HashSet<>(ids).size()) {
            throw new PlatformException("目标人员不可用或不在可选范围内");
        }

    }

    private Map<String, Object> relations(EntityContract record) {
        DataScopeCapable value = (DataScopeCapable) record;
        return Map.of("ownerId", value.getAuthUserId() == null ? "" : value.getAuthUserId(),
                "assigneeIds", RecordPermissionChanges.ids(value.getAuthAssigneeIds()),
                "memberIds", RecordPermissionChanges.ids(value.getAuthMemberIds()));
    }

    private Criteria candidateCriteria(EntityContract record) {
        Criteria criteria = Criteria.of().eq("enabled", true);
        return record.getTenantId() == null ? criteria.isNull("tenantId") : criteria.eq("tenantId", record.getTenantId());
    }

    private <T> T withTenantScope(RecordPermissionAccess<?> access, java.util.function.Supplier<T> supplier) {
        if (access.crossTenant()) {
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter(
                    "record permission action allows cross-tenant reference read")) {
                return supplier.get();
            }
        }
        return supplier.get();
    }

    private ReferenceAbility<?> users() {
        return PlatformAbilityRuntime.referenceTargetResolver().resolve(ReferenceTarget.parse("iam.user"))
                .orElseThrow(() -> new PlatformException("用户引用能力不可用"));
    }

    private RecordPermissionState state(EntityContract entity) {
        DataScopeCapable value = (DataScopeCapable) entity;
        List<String> assignees = RecordPermissionChanges.ids(value.getAuthAssigneeIds());
        List<String> members = RecordPermissionChanges.ids(value.getAuthMemberIds());
        Set<String> ids = new LinkedHashSet<>(assignees);
        ids.addAll(members);
        if (value.getAuthUserId() != null) ids.add(value.getAuthUserId());
        return new RecordPermissionState(entity.getVersion(), value.getAuthUserId(), assignees, members, users().titles(ids));
    }
}
