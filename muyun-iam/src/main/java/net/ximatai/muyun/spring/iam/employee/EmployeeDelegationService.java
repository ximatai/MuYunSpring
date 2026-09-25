package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.BusinessPrincipal;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.util.Preconditions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EmployeeDelegationService extends TenantActiveScopedService<EmployeeDelegation> implements
        EnableAbility<EmployeeDelegation> {
    public static final String MODULE_ALIAS = "iam.employee_delegation";

    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final EmployeeService employeeService;
    private final EmployeePositionService employeePositionService;
    private final EmployeeAccountService employeeAccountService;

    @Autowired
    public EmployeeDelegationService(EmployeeDelegationDao employeeDelegationDao,
                                     ActiveTenantVerifier activeTenantVerifier,
                                     EmployeeService employeeService,
                                     EmployeePositionService employeePositionService,
                                     EmployeeAccountService employeeAccountService) {
        super(MODULE_ALIAS, EmployeeDelegation.class, employeeDelegationDao, activeTenantVerifier);
        this.employeeService = employeeService;
        this.employeePositionService = employeePositionService;
        this.employeeAccountService = employeeAccountService;
    }

    @Override
    public void normalizeBeforeMutation(EmployeeDelegation delegation) {
        if (delegation.getDelegationType() == null) {
            delegation.setDelegationType(EmployeeDelegationType.BUSINESS);
        }
        if (delegation.getModuleScopeType() == null) {
            delegation.setModuleScopeType(EmployeeDelegationScopeType.ALL);
        }
        delegation.setModuleAliases(delegation.getModuleScopeType() == EmployeeDelegationScopeType.ALL
                ? Set.of()
                : normalizeModuleAliases(delegation.getModuleAliases()));
        if (delegation.getActionScopeType() == null) {
            delegation.setActionScopeType(EmployeeDelegationScopeType.ALL);
        }
        delegation.setActionKeys(delegation.getActionScopeType() == EmployeeDelegationScopeType.ALL
                ? Set.of()
                : normalizeActionKeys(delegation.getActionKeys()));
        if (delegation.getEnabled() == null) {
            delegation.setEnabled(Boolean.TRUE);
        }
    }

    @Override
    protected void validateBeforeSave(EmployeeDelegation delegation) {
        // Reference-dependent checks run only when their inputs exist; the write chain enforces required fields.
        if (!StringUtils.hasText(delegation.getPrincipalEmployeeId())
                || !StringUtils.hasText(delegation.getDelegateEmployeeId())) return;
        validateDelegationReferences(delegation);
        rejectDuplicate(delegation, duplicateCriteria(delegation), "employee delegation already exists");
    }

    public List<EmployeeDelegation> delegationsByPrincipal(String principalEmployeeId) {
        String validPrincipal = Preconditions.requireText(principalEmployeeId, "principalEmployeeId");
        return list(Criteria.of().eq("principalEmployeeId", validPrincipal), ALL,
                Sort.desc("updatedAt"), Sort.desc("createdAt"));
    }

    public List<EmployeeDelegation> delegationsByDelegate(String delegateEmployeeId) {
        String validDelegate = Preconditions.requireText(delegateEmployeeId, "delegateEmployeeId");
        return list(Criteria.of().eq("delegateEmployeeId", validDelegate), ALL,
                Sort.desc("updatedAt"), Sort.desc("createdAt"));
    }

    public String addDelegation(String principalEmployeeId, EmployeeDelegation delegation) {
        delegation.setPrincipalEmployeeId(Preconditions.requireText(principalEmployeeId, "principalEmployeeId"));
        return insert(delegation);
    }

    public int updateDelegation(String principalEmployeeId, String delegationId, EmployeeDelegation delegation) {
        EmployeeDelegation existing = requirePrincipalDelegation(principalEmployeeId, delegationId);
        delegation.setId(Preconditions.requireText(delegationId, "delegationId"));
        delegation.setPrincipalEmployeeId(Preconditions.requireText(principalEmployeeId, "principalEmployeeId"));
        delegation.setEnabled(existing.getEnabled());
        preserveScopeIfMissing(existing, delegation);
        return update(delegation);
    }

    public int deleteDelegation(String principalEmployeeId, String delegationId) {
        EmployeeDelegation delegation = requirePrincipalDelegation(principalEmployeeId, delegationId);
        return delete(delegation);
    }

    public int enableDelegation(String principalEmployeeId, String delegationId) {
        requirePrincipalDelegation(principalEmployeeId, delegationId);
        return enable(delegationId);
    }

    public int disableDelegation(String principalEmployeeId, String delegationId) {
        requirePrincipalDelegation(principalEmployeeId, delegationId);
        return disable(delegationId);
    }

    public ActingContext resolveActingContext(CurrentUser operator,
                                              String principalEmployeeId,
                                              String principalPositionId,
                                              String moduleAlias,
                                              String actionCode) {
        String delegateEmployeeId = java.util.Objects.requireNonNull(employeeAccountService, "employeeAccountService")
                .employeeIdOfUser(java.util.Objects.requireNonNull(operator, "operator must not be null").userId());
        return resolveActingContext(operator, delegateEmployeeId, null,
                principalEmployeeId, principalPositionId, moduleAlias, actionCode, Instant.now());
    }

    public ActingContext resolveActingContext(CurrentUser operator,
                                              String delegateEmployeeId,
                                              String delegatePositionId,
                                              String principalEmployeeId,
                                              String principalPositionId,
                                              String moduleAlias,
                                              String actionCode) {
        return resolveActingContext(operator, delegateEmployeeId, delegatePositionId,
                principalEmployeeId, principalPositionId, moduleAlias, actionCode, Instant.now());
    }

    public ActingContext resolveActingContext(CurrentUser operator,
                                              String delegateEmployeeId,
                                              String delegatePositionId,
                                              String principalEmployeeId,
                                              String principalPositionId,
                                              String moduleAlias,
                                              String actionCode,
                                              Instant now) {
        CurrentUser validOperator = java.util.Objects.requireNonNull(operator, "operator must not be null");
        String validDelegate = Preconditions.requireText(delegateEmployeeId, "delegateEmployeeId");
        String validPrincipal = Preconditions.requireText(principalEmployeeId, "principalEmployeeId");
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        String validActionCode = PlatformNameRules.requireActionCode(actionCode, "actionCode");
        Instant validNow = java.util.Objects.requireNonNull(now, "now must not be null");

        String operatorEmployeeId = employeeAccountService.employeeIdOfUser(validOperator.userId());
        if (!validDelegate.equals(operatorEmployeeId)) {
            throw BusinessExceptions.warning("iam.employee-delegation.operator-not-delegate",
                    "operator is not bound to delegate employee: " + validDelegate);
        }
        employeeService.requireEnabled(validDelegate, "delegate employee is not active: " + validDelegate);
        Employee principal = employeeService.requireEnabled(validPrincipal,
                "principal employee is not active: " + validPrincipal);
        String validDelegatePosition = normalizeBlank(delegatePositionId);
        String validPrincipalPosition = normalizeBlank(principalPositionId);
        if (validDelegatePosition != null) {
            requirePositionOwner(validDelegatePosition, validDelegate,
                    "iam.employee-delegation.delegate-position-owner-mismatch",
                    "delegate position does not belong to delegate employee: ");
        }
        EmployeePosition principalPosition = null;
        if (validPrincipalPosition != null) {
            principalPosition = requirePositionOwner(validPrincipalPosition, validPrincipal,
                    "iam.employee-delegation.principal-position-owner-mismatch",
                    "principal position does not belong to principal employee: ");
        }

        EmployeeDelegation matched = activeDelegations(validPrincipal, validDelegate).stream()
                .filter(delegation -> matchesDelegation(delegation, validNow, validPrincipalPosition,
                        validDelegatePosition, validModuleAlias, validActionCode))
                .max(Comparator.comparingInt(this::specificityScore)
                        .thenComparing(EmployeeDelegation::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(EmployeeDelegation::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(EmployeeDelegation::getId, Comparator.nullsFirst(String::compareTo)))
                .orElseThrow(() -> BusinessExceptions.warning("iam.employee-delegation.not-allowed",
                        "employee delegation is not allowed"));

        BusinessPrincipal businessPrincipal = principalPosition == null
                ? BusinessPrincipal.employee(principal.getId(), principal.getOrganizationId(), principal.getDepartmentId())
                : BusinessPrincipal.employeePosition(principal.getId(), principalPosition.getOrganizationId(),
                principalPosition.getDepartmentId(), principalPosition.getId());
        return new ActingContext(matched.getId(), validOperator, businessPrincipal, validModuleAlias, validActionCode);
    }

    private EmployeeDelegation requirePrincipalDelegation(String principalEmployeeId, String delegationId) {
        String validPrincipal = Preconditions.requireText(principalEmployeeId, "principalEmployeeId");
        String validDelegationId = Preconditions.requireText(delegationId, "delegationId");
        EmployeeDelegation delegation = select(validDelegationId);
        if (delegation == null || !validPrincipal.equals(delegation.getPrincipalEmployeeId())) {
            throw BusinessExceptions.warning("iam.employee-delegation.not-principal-owned",
                    "employee delegation does not belong to principal employee: " + validDelegationId);
        }
        return delegation;
    }

    private void validateDelegationReferences(EmployeeDelegation delegation) {
        if (delegation.getPrincipalEmployeeId().equals(delegation.getDelegateEmployeeId())) {
            throw BusinessExceptions.warning("iam.employee-delegation.self-delegation-denied",
                    "employee delegation delegate must differ from principal");
        }
        employeeService.requireEnabled(delegation.getPrincipalEmployeeId(),
                "principal employee is not active: " + delegation.getPrincipalEmployeeId());
        employeeService.requireEnabled(delegation.getDelegateEmployeeId(),
                "delegate employee is not active: " + delegation.getDelegateEmployeeId());
        requirePositionOwner(delegation.getPrincipalPositionId(), delegation.getPrincipalEmployeeId(),
                "iam.employee-delegation.principal-position-owner-mismatch",
                "principal position does not belong to principal employee: ");
        requirePositionOwner(delegation.getDelegatePositionId(), delegation.getDelegateEmployeeId(),
                "iam.employee-delegation.delegate-position-owner-mismatch",
                "delegate position does not belong to delegate employee: ");
        if (delegation.getEffectiveFrom() != null && delegation.getEffectiveTo() != null
                && !delegation.getEffectiveFrom().isBefore(delegation.getEffectiveTo())) {
            throw BusinessExceptions.warning("iam.employee-delegation.invalid-effective-range",
                    "employee delegation effectiveFrom must be before effectiveTo");
        }
        if (delegation.getModuleScopeType() == EmployeeDelegationScopeType.INCLUDE
                && isEmpty(delegation.getModuleAliases())) {
            throw BusinessExceptions.warning("iam.employee-delegation.module-scope-required",
                    "employee delegation moduleAliases are required for INCLUDE scope");
        }
        if (delegation.getActionScopeType() == EmployeeDelegationScopeType.INCLUDE
                && isEmpty(delegation.getActionKeys())) {
            throw BusinessExceptions.warning("iam.employee-delegation.action-scope-required",
                    "employee delegation actionKeys are required for INCLUDE scope");
        }
    }

    private EmployeePosition requirePositionOwner(String positionId, String employeeId, String code, String message) {
        if (positionId == null) {
            return null;
        }
        EmployeePosition position = employeePositionService.select(positionId);
        if (position == null
                || !Boolean.TRUE.equals(position.getEnabled())
                || !employeeId.equals(position.getEmployeeId())) {
            throw BusinessExceptions.warning(code, message + positionId);
        }
        return position;
    }

    private void preserveScopeIfMissing(EmployeeDelegation existing, EmployeeDelegation incoming) {
        if (incoming.getModuleScopeType() == null) {
            incoming.setModuleScopeType(existing.getModuleScopeType());
        }
        if (incoming.getModuleAliases() == null) {
            incoming.setModuleAliases(existing.getModuleAliases());
        }
        if (incoming.getActionScopeType() == null) {
            incoming.setActionScopeType(existing.getActionScopeType());
        }
        if (incoming.getActionKeys() == null) {
            incoming.setActionKeys(existing.getActionKeys());
        }
    }

    private Criteria duplicateCriteria(EmployeeDelegation delegation) {
        Criteria criteria = Criteria.of()
                .eq("delegationType", delegation.getDelegationType())
                .eq("principalEmployeeId", delegation.getPrincipalEmployeeId())
                .eq("delegateEmployeeId", delegation.getDelegateEmployeeId());
        eqOrIsNull(criteria, "principalPositionId", delegation.getPrincipalPositionId());
        eqOrIsNull(criteria, "delegatePositionId", delegation.getDelegatePositionId());
        eqOrIsNull(criteria, "effectiveFrom", delegation.getEffectiveFrom());
        eqOrIsNull(criteria, "effectiveTo", delegation.getEffectiveTo());
        return criteria;
    }

    private List<EmployeeDelegation> activeDelegations(String principalEmployeeId, String delegateEmployeeId) {
        return list(Criteria.of()
                        .eq("delegationType", EmployeeDelegationType.BUSINESS)
                        .eq("principalEmployeeId", principalEmployeeId)
                        .eq("delegateEmployeeId", delegateEmployeeId)
                        .eq("enabled", Boolean.TRUE),
                ALL, Sort.asc("createdAt"));
    }

    private boolean matchesDelegation(EmployeeDelegation delegation,
                                      Instant now,
                                      String principalPositionId,
                                      String delegatePositionId,
                                      String moduleAlias,
                                      String actionCode) {
        return inEffectiveRange(delegation, now)
                && Boolean.TRUE.equals(delegation.getEnabled())
                && matchesOptionalBound(delegation.getPrincipalPositionId(), principalPositionId)
                && matchesOptionalBound(delegation.getDelegatePositionId(), delegatePositionId)
                && matchesScope(delegation.getModuleScopeType(), delegation.getModuleAliases(), moduleAlias)
                && matchesScope(delegation.getActionScopeType(), delegation.getActionKeys(),
                actionKey(moduleAlias, actionCode));
    }

    private boolean inEffectiveRange(EmployeeDelegation delegation, Instant now) {
        return (delegation.getEffectiveFrom() == null || !now.isBefore(delegation.getEffectiveFrom()))
                && (delegation.getEffectiveTo() == null || now.isBefore(delegation.getEffectiveTo()));
    }

    private boolean matchesOptionalBound(String configured, String requested) {
        return configured == null || configured.equals(requested);
    }

    private boolean matchesScope(EmployeeDelegationScopeType type, Set<String> values, String value) {
        if (type == EmployeeDelegationScopeType.ALL) {
            return true;
        }
        return value != null && normalizeSet(values).contains(value);
    }

    private int specificityScore(EmployeeDelegation delegation) {
        int score = 0;
        if (delegation.getPrincipalPositionId() != null) {
            score++;
        }
        if (delegation.getDelegatePositionId() != null) {
            score++;
        }
        if (delegation.getModuleScopeType() == EmployeeDelegationScopeType.INCLUDE) {
            score++;
        }
        if (delegation.getActionScopeType() == EmployeeDelegationScopeType.INCLUDE) {
            score += 2;
        }
        return score;
    }

    private void eqOrIsNull(Criteria criteria, String field, Object value) {
        if (value == null) {
            criteria.isNull(field);
            return;
        }
        criteria.eq(field, value);
    }

    private Set<String> normalizeModuleAliases(Set<String> values) {
        return normalizeSet(values).stream()
                .map(PlatformNameRules::requireModuleAlias)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> normalizeActionKeys(Set<String> values) {
        return normalizeSet(values).stream()
                .map(this::requireActionKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isEmpty(Set<String> values) {
        return values == null || values.isEmpty();
    }

    private Set<String> normalizeSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String requireActionKey(String value) {
        String normalized = Preconditions.requireText(value, "actionKey");
        int separator = normalized.indexOf('#');
        if (separator <= 0 || separator == normalized.length() - 1 || separator != normalized.lastIndexOf('#')) {
            throw new IllegalArgumentException("invalid actionKey: " + value);
        }
        return actionKey(normalized.substring(0, separator), normalized.substring(separator + 1));
    }

    private String actionKey(String moduleAlias, String actionCode) {
        return PlatformNameRules.requireModuleAlias(moduleAlias)
                + "#"
                + PlatformNameRules.requireActionCode(actionCode, "actionCode");
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
