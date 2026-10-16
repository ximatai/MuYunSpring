package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;

@Service
public class WorkflowDelegationService extends AbstractAbilityService<WorkflowDelegation> {
    public static final String MODULE_ALIAS = "platform.workflow_delegation";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    public WorkflowDelegationService(BaseDao<WorkflowDelegation, String> workflowDelegationDao) {
        super(MODULE_ALIAS, WorkflowDelegation.class, workflowDelegationDao);
    }

    @Override
    public void beforeInsert(WorkflowDelegation delegation) {
        delegation.setEnabled(Boolean.FALSE);
        normalizeAndValidate(delegation);
    }

    @Override
    public void beforeUpdate(WorkflowDelegation delegation) {
        WorkflowDelegation existing = requireExisting(delegation.getId());
        if (Boolean.TRUE.equals(existing.getEnabled())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-delegation.disable-the-approval-delegation-before-editing-it",
                    "enabled workflow delegation cannot be updated: {value1}"), Map.of("value1", String.valueOf(delegation.getId())));
        }
        delegation.setEnabled(Boolean.FALSE);
        normalizeAndValidate(delegation);
    }

    @Override
    public void beforeDelete(String id) {
        WorkflowDelegation existing = requireExisting(id);
        if (Boolean.TRUE.equals(existing.getEnabled())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-delegation.disable-the-approval-delegation-before-deleting-it",
                    "enabled workflow delegation cannot be deleted: {value1}"), Map.of("value1", String.valueOf(id)));
        }
    }

    public WorkflowDelegation insertForPrincipal(WorkflowDelegation delegation, String principalUserId) {
        String currentPrincipal = requireText(principalUserId, TextReference.code(
                "platform.rejection.workflow-delegation.select-an-approval-principal",
                "workflow delegation principal user id must not be blank"));
        delegation.setPrincipalUserId(currentPrincipal);
        String id = insert(delegation);
        return select(id);
    }

    public WorkflowDelegation updateForPrincipal(String id, WorkflowDelegation delegation, String principalUserId) {
        WorkflowDelegation existing = requireExisting(id);
        String currentPrincipal = requireText(principalUserId, TextReference.code(
                "platform.rejection.workflow-delegation.select-an-approval-principal",
                "workflow delegation principal user id must not be blank"));
        if (!currentPrincipal.equals(existing.getPrincipalUserId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-delegation.you-can-only-manage-your-own-approval-delegations",
                    "workflow delegation does not belong to current principal: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        delegation.setId(id);
        delegation.setPrincipalUserId(currentPrincipal);
        update(delegation);
        return select(id);
    }

    public int deleteForPrincipal(String id, String principalUserId) {
        return deleteForPrincipal(id, null, principalUserId);
    }

    public int deleteForPrincipal(String id, Integer expectedVersion, String principalUserId) {
        WorkflowDelegation existing = requireExisting(id);
        String currentPrincipal = requireText(principalUserId, TextReference.code(
                "platform.rejection.workflow-delegation.select-an-approval-principal",
                "workflow delegation principal user id must not be blank"));
        if (!currentPrincipal.equals(existing.getPrincipalUserId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-delegation.you-can-only-manage-your-own-approval-delegations",
                    "workflow delegation does not belong to current principal: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        requireExpectedVersion(existing, expectedVersion);
        return delete(id, expectedVersion);
    }

    public WorkflowDelegation enable(String id) {
        return enable(id, null);
    }

    public WorkflowDelegation enable(String id, Integer expectedVersion) {
        WorkflowDelegation existing = requireExisting(id);
        requireExpectedVersion(existing, expectedVersion);
        if (Boolean.TRUE.equals(existing.getEnabled())) {
            return existing;
        }
        rejectEnabledConflict(existing);
        existing.setVersion(expectedVersion == null ? existing.getVersion() : expectedVersion);
        return updateEnabled(existing, Boolean.TRUE);
    }

    public WorkflowDelegation disable(String id) {
        return disable(id, null);
    }

    public WorkflowDelegation disable(String id, Integer expectedVersion) {
        WorkflowDelegation existing = requireExisting(id);
        requireExpectedVersion(existing, expectedVersion);
        if (!Boolean.TRUE.equals(existing.getEnabled())) {
            return existing;
        }
        existing.setVersion(expectedVersion == null ? existing.getVersion() : expectedVersion);
        return updateEnabled(existing, Boolean.FALSE);
    }

    public WorkflowDelegation enableForPrincipal(String id, String principalUserId) {
        return enableForPrincipal(id, null, principalUserId);
    }

    public WorkflowDelegation enableForPrincipal(String id, Integer expectedVersion, String principalUserId) {
        requirePrincipal(id, principalUserId);
        return enable(id, expectedVersion);
    }

    public WorkflowDelegation disableForPrincipal(String id, String principalUserId) {
        return disableForPrincipal(id, null, principalUserId);
    }

    public WorkflowDelegation disableForPrincipal(String id, Integer expectedVersion, String principalUserId) {
        requirePrincipal(id, principalUserId);
        return disable(id, expectedVersion);
    }

    public List<WorkflowDelegation> queryByPrincipal(String principalUserId, PageRequest pageRequest) {
        return pageByPrincipal(principalUserId, pageRequest).getRecords();
    }

    public List<WorkflowDelegation> queryByDelegate(String delegateUserId, PageRequest pageRequest) {
        return pageByDelegate(delegateUserId, pageRequest).getRecords();
    }

    public PageResult<WorkflowDelegation> pageByPrincipal(String principalUserId, PageRequest pageRequest) {
        return pageByPrincipal(principalUserId, Criteria.of(), pageRequest);
    }

    public PageResult<WorkflowDelegation> pageByPrincipal(String principalUserId, Criteria criteria,
                                                          PageRequest pageRequest) {
        Criteria scoped = criteria == null ? Criteria.of() : criteria;
        scoped.eq("principalUserId", requireText(principalUserId, TextReference.code(
                "platform.rejection.workflow-delegation.select-an-approval-principal",
                "workflow delegation principal user id must not be blank")));
        return pageQuery(scoped, page(pageRequest),
                Sort.desc("updatedAt"), Sort.desc("createdAt"));
    }

    public PageResult<WorkflowDelegation> pageByDelegate(String delegateUserId, PageRequest pageRequest) {
        return pageByDelegate(delegateUserId, Criteria.of(), pageRequest);
    }

    public PageResult<WorkflowDelegation> pageByDelegate(String delegateUserId, Criteria criteria,
                                                         PageRequest pageRequest) {
        Criteria scoped = criteria == null ? Criteria.of() : criteria;
        scoped.eq("delegateUserId", requireText(delegateUserId, TextReference.code(
                "platform.rejection.workflow-delegation.select-an-approval-delegate",
                "workflow delegation delegate user id must not be blank")));
        return pageQuery(scoped, page(pageRequest),
                Sort.desc("updatedAt"), Sort.desc("createdAt"));
    }

    public WorkflowDelegationMatch match(String principalUserId, String moduleAlias, String authOrgId) {
        String validPrincipal = requireText(principalUserId, TextReference.code(
                "platform.rejection.workflow-delegation.select-a-principal",
                "workflow principal user id must not be blank"));
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        List<WorkflowDelegation> candidates = list(Criteria.of()
                        .eq("principalUserId", validPrincipal)
                        .eq("enabled", Boolean.TRUE),
                ALL, Sort.asc("createdAt"));
        List<ScoredDelegation> matches = candidates.stream()
                .filter(delegation -> matches(delegation, validModuleAlias, authOrgId))
                .map(delegation -> new ScoredDelegation(delegation, score(delegation)))
                .toList();
        if (matches.isEmpty()) {
            return null;
        }
        int maxScore = matches.stream().mapToInt(ScoredDelegation::score).max().orElse(0);
        List<WorkflowDelegation> strongest = matches.stream()
                .filter(match -> match.score() == maxScore)
                .map(ScoredDelegation::delegation)
                .toList();
        Set<String> delegates = strongest.stream()
                .map(WorkflowDelegation::getDelegateUserId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (delegates.size() > 1) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-delegation.conflicting-delegation-rules-match-this-principal",
                    "workflow delegation match conflict for principal: {value1}"), Map.of("value1", String.valueOf(validPrincipal)));
        }
        WorkflowDelegation selected = strongest.stream()
                .sorted(Comparator.comparing(WorkflowDelegation::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(WorkflowDelegation::getId, Comparator.nullsLast(String::compareTo)))
                .findFirst()
                .orElseThrow();
        return new WorkflowDelegationMatch(selected.getId(), validPrincipal, selected.getDelegateUserId(),
                Boolean.TRUE.equals(selected.getPrincipalCanProcess()), snapshot(selected, validModuleAlias, authOrgId));
    }

    private WorkflowDelegation requirePrincipal(String id, String principalUserId) {
        WorkflowDelegation existing = requireExisting(id);
        String currentPrincipal = requireText(principalUserId, TextReference.code(
                "platform.rejection.workflow-delegation.select-an-approval-principal",
                "workflow delegation principal user id must not be blank"));
        if (!currentPrincipal.equals(existing.getPrincipalUserId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-delegation.you-can-only-manage-your-own-approval-delegations",
                    "workflow delegation does not belong to current principal: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        return existing;
    }

    private WorkflowDelegation updateEnabled(WorkflowDelegation delegation, Boolean enabled) {
        Integer expectedVersion = delegation.getVersion();
        delegation.setEnabled(enabled);
        EntityLifecycle.prepareUpdate(delegation, Instant.now(), EntityLifecycle.nextVersion(delegation.getVersion()));
        int updated = getDao().updateByIdAndVersion(delegation, expectedVersion);
        if (updated <= 0) {
            throw new OptimisticLockException("workflow delegation version conflict: " + delegation.getId());
        }
        return select(delegation.getId());
    }

    private void requireExpectedVersion(WorkflowDelegation delegation, Integer expectedVersion) {
        if (expectedVersion != null && !expectedVersion.equals(delegation.getVersion())) {
            throw new OptimisticLockException("workflow delegation version conflict: " + delegation.getId());
        }
    }

    private void rejectEnabledConflict(WorkflowDelegation delegation) {
        List<WorkflowDelegation> existing = list(Criteria.of()
                        .eq("principalUserId", delegation.getPrincipalUserId())
                        .eq("enabled", Boolean.TRUE),
                ALL, Sort.asc("createdAt"));
        for (WorkflowDelegation other : existing) {
            if (Objects.equals(other.getId(), delegation.getId())) {
                continue;
            }
            if (score(other) == score(delegation)
                    && other.getModuleScopeType() == delegation.getModuleScopeType()
                    && other.getOrgScopeType() == delegation.getOrgScopeType()
                    && !Objects.equals(other.getDelegateUserId(), delegation.getDelegateUserId())
                    && overlaps(other.getModuleScopeType(), other.getModuleAliases(),
                    delegation.getModuleScopeType(), delegation.getModuleAliases())
                    && overlaps(other.getOrgScopeType(), other.getOrgIds(),
                    delegation.getOrgScopeType(), delegation.getOrgIds())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-delegation.an-enabled-approval-delegation-conflicts-with-this-rule",
                        "workflow delegation enabled conflict: {value1}"), Map.of("value1", String.valueOf(delegation.getPrincipalUserId())));
            }
        }
    }

    private boolean overlaps(WorkflowDelegationScopeType leftType, Set<String> leftValues,
                             WorkflowDelegationScopeType rightType, Set<String> rightValues) {
        if (leftType == WorkflowDelegationScopeType.ALL || rightType == WorkflowDelegationScopeType.ALL) {
            return true;
        }
        Set<String> left = normalizeSet(leftValues);
        Set<String> right = normalizeSet(rightValues);
        return left.stream().anyMatch(right::contains);
    }

    private boolean matches(WorkflowDelegation delegation, String moduleAlias, String authOrgId) {
        return !sameText(delegation.getPrincipalUserId(), delegation.getDelegateUserId())
                && matchesScope(delegation.getModuleScopeType(), delegation.getModuleAliases(), moduleAlias)
                && matchesScope(delegation.getOrgScopeType(), delegation.getOrgIds(), authOrgId);
    }

    private boolean matchesScope(WorkflowDelegationScopeType type, Set<String> values, String value) {
        if (type == WorkflowDelegationScopeType.ALL) {
            return true;
        }
        return value != null && !value.isBlank() && normalizeSet(values).contains(value);
    }

    private int score(WorkflowDelegation delegation) {
        int score = 0;
        if (delegation.getModuleScopeType() == WorkflowDelegationScopeType.INCLUDE) {
            score++;
        }
        if (delegation.getOrgScopeType() == WorkflowDelegationScopeType.INCLUDE) {
            score++;
        }
        return score;
    }

    private void normalizeAndValidate(WorkflowDelegation delegation) {
        if (sameText(delegation.getPrincipalUserId(), delegation.getDelegateUserId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-delegation.the-principal-and-delegate-must-be-different-people",
                    "workflow delegation delegate must differ from principal"));
        }
        if (delegation.getPrincipalCanProcess() == null) {
            delegation.setPrincipalCanProcess(Boolean.FALSE);
        }
        if (delegation.getModuleScopeType() == null) {
            delegation.setModuleScopeType(WorkflowDelegationScopeType.ALL);
        }
        delegation.setModuleAliases(normalizeModules(delegation.getModuleAliases()));
        if (delegation.getModuleScopeType() == WorkflowDelegationScopeType.INCLUDE
                && delegation.getModuleAliases().isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-delegation.select-modules-for-the-included-scope",
                    "workflow delegation moduleAliases are required for INCLUDE scope"));
        }
        if (delegation.getOrgScopeType() == null) {
            delegation.setOrgScopeType(WorkflowDelegationScopeType.ALL);
        }
        delegation.setOrgIds(normalizeSet(delegation.getOrgIds()));
        if (delegation.getOrgScopeType() == WorkflowDelegationScopeType.INCLUDE
                && delegation.getOrgIds().isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-delegation.select-organizations-for-the-included-scope",
                    "workflow delegation orgIds are required for INCLUDE scope"));
        }
    }

    private Set<String> normalizeModules(Set<String> values) {
        return normalizeSet(values).stream()
                .map(PlatformNameRules::requireModuleAlias)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> normalizeSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private WorkflowDelegation requireExisting(String id) {
        WorkflowDelegation existing = selectActiveRaw(requireText(id, TextReference.code(
                "platform.rejection.workflow-delegation.select-an-approval-delegation-rule",
                "workflow delegation id must not be blank")));
        if (existing == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-delegation.approval-delegation-not-found",
                    "workflow delegation not found: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        return existing;
    }

    private PageRequest page(PageRequest pageRequest) {
        return pageRequest == null ? PageRequest.of(1, 20) : pageRequest;
    }

    private String snapshot(WorkflowDelegation delegation, String moduleAlias, String authOrgId) {
        return "{\"delegationPolicyId\":\"" + escape(delegation.getId())
                + "\",\"principalUserId\":\"" + escape(delegation.getPrincipalUserId())
                + "\",\"delegateUserId\":\"" + escape(delegation.getDelegateUserId())
                + "\",\"principalCanProcess\":" + Boolean.TRUE.equals(delegation.getPrincipalCanProcess())
                + ",\"moduleScopeType\":\"" + delegation.getModuleScopeType().getCode()
                + "\",\"orgScopeType\":\"" + delegation.getOrgScopeType().getCode()
                + "\",\"matchedModuleAlias\":\"" + escape(moduleAlias)
                + "\",\"matchedOrgId\":" + (authOrgId == null ? "null" : "\"" + escape(authOrgId) + "\"")
                + "}";
    }

    private String currentUserId() {
        return CurrentUserContext.currentUser()
                .map(user -> user.userId())
                .filter(value -> value != null && !value.isBlank())
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-delegation.sign-in-before-managing-approval-delegation",
                        "current user is required for workflow delegation")));
    }

    public String currentPrincipalUserId() {
        return currentUserId();
    }

    private boolean sameText(String left, String right) {
        return left != null && right != null && left.equals(right);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String requireText(String value, TextReference source) {
        if (value == null || value.isBlank()) {
            throw PlatformErrors.badRequestText(source);
        }
        return value.trim();
    }

    private record ScoredDelegation(WorkflowDelegation delegation, int score) {
    }
}
