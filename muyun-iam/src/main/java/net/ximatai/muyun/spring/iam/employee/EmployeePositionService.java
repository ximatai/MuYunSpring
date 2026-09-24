package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.util.Preconditions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EmployeePositionService extends TenantActiveScopedService<EmployeePosition> implements
        SoftDeleteAbility<EmployeePosition>,
        EnableAbility<EmployeePosition>,
        SortAbility<EmployeePosition>,
        ChildAbility<EmployeePosition> {
    public static final String MODULE_ALIAS = "iam.employee_position";

    private final EmployeeService employeeService;

    @Autowired
    public EmployeePositionService(EmployeePositionDao employeePositionDao,
                                   ActiveTenantVerifier activeTenantVerifier,
                                   EmployeeService employeeService) {
        super(MODULE_ALIAS, EmployeePosition.class, employeePositionDao, activeTenantVerifier);
        this.employeeService = employeeService;
    }

    @Override
    public void normalizeBeforeMutation(EmployeePosition relation) {
        relation.setEmployeeId(Preconditions.requireText(relation.getEmployeeId(), "employeeId"));
        relation.setOrganizationId(Preconditions.requireText(relation.getOrganizationId(), "organizationId"));
        relation.setDepartmentId(Preconditions.requireText(relation.getDepartmentId(), "departmentId"));
        relation.setPositionId(Preconditions.requireText(relation.getPositionId(), "positionId"));
        relation.setPrimaryPosition(Boolean.TRUE.equals(relation.getPrimaryPosition()));
    }

    @Override
    protected void validateBeforeSave(EmployeePosition relation) {
        if (Boolean.TRUE.equals(relation.getPrimaryPosition()) && Boolean.TRUE.equals(relation.getEnabled())) {
            validatePrimaryPositionOwner(relation);
            rejectDuplicate(relation, Criteria.of()
                    .eq("employeeId", relation.getEmployeeId())
                            .eq("primaryPosition", Boolean.TRUE)
                            .eq("enabled", Boolean.TRUE),
                    () -> BusinessExceptions.warning("iam.employee-position.primary-already-exists",
                            "该职员已有主岗位"));
        }
        rejectDuplicate(relation, Criteria.of()
                        .eq("employeeId", relation.getEmployeeId())
                        .eq("organizationId", relation.getOrganizationId())
                        .eq("departmentId", relation.getDepartmentId())
                        .eq("positionId", relation.getPositionId()),
                () -> BusinessExceptions.warning("iam.employee-position.already-exists",
                        "该职员已存在相同任职"));
    }

    @Override
    public void beforeUpdate(EmployeePosition relation, EmployeePosition existing) {
        if (existing != null && !java.util.Objects.equals(existing.getEmployeeId(), relation.getEmployeeId())) {
            throw BusinessExceptions.warning("iam.employee-position.owner-immutable", "任职不能转移至其他职员");
        }
        super.beforeUpdate(relation);
    }

    @Override
    public void beforeRestore(String id) {
        EmployeePosition retained = selectIgnoreSoftDelete(id);
        if (retained != null && Boolean.TRUE.equals(retained.getDeleted())) validateBeforeSave(retained);
    }

    @Override
    public Function<EmployeePosition, String> mutationParentKey() {
        return EmployeePosition::getEmployeeId;
    }

    public List<EmployeePosition> positions(String employeeId) {
        String validEmployeeId = Preconditions.requireText(employeeId, "employeeId");
        return list(employeeCriteria(validEmployeeId), new PageRequest(0, Integer.MAX_VALUE),
                Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    /**
     * Reads the persisted employment facts needed while resolving a principal's roles.
     *
     * <p>Authorization resolution must not trigger page-oriented option or reference projections:
     * those projections may themselves require the caller's data scope, which is derived from the
     * same role facts.</p>
     */
    public List<EmployeePosition> activePositionsForRoleResolution(String employeeId) {
        String validEmployeeId = Preconditions.requireText(employeeId, "employeeId");
        return listRaw(employeeCriteria(validEmployeeId), new PageRequest(0, Integer.MAX_VALUE),
                Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    /**
     * A primary-position switch is an ordinary checkbox edit in the aggregate grid. Persist
     * outgoing primary rows first so the existing single-primary invariant remains valid at
     * every individual child write; no special frontend interaction is required.
     */
    @Override
    public List<EmployeePosition> orderForReplacement(List<EmployeePosition> incoming,
                                                       List<EmployeePosition> existing) {
        if (incoming == null || incoming.size() < 2 || existing == null || existing.isEmpty()) {
            return incoming == null ? List.of() : incoming;
        }
        Map<String, EmployeePosition> existingById = existing.stream()
                .filter(value -> value.getId() != null)
                .collect(Collectors.toMap(EmployeePosition::getId, Function.identity()));
        return incoming.stream()
                .sorted((left, right) -> Boolean.compare(
                        retiresActivePrimary(existingById.get(right.getId()), right),
                        retiresActivePrimary(existingById.get(left.getId()), left)))
                .toList();
    }

    public String addPosition(String employeeId, EmployeePosition relation) {
        relation.setEmployeeId(Preconditions.requireText(employeeId, "employeeId"));
        return insert(relation);
    }

    public int updatePosition(String employeeId, String relationId, EmployeePosition relation) {
        requireEmployeePosition(employeeId, relationId);
        relation.setId(Preconditions.requireText(relationId, "relationId"));
        relation.setEmployeeId(Preconditions.requireText(employeeId, "employeeId"));
        return update(relation);
    }

    public int deletePosition(String employeeId, String relationId) {
        EmployeePosition relation = requireEmployeePosition(employeeId, relationId);
        return delete(relation);
    }

    public int enablePosition(String employeeId, String relationId) {
        requireEmployeePosition(employeeId, relationId);
        return enable(relationId);
    }

    public int disablePosition(String employeeId, String relationId) {
        requireEmployeePosition(employeeId, relationId);
        return disable(relationId);
    }

    @Transactional
    public int makePrimaryPosition(String employeeId, String relationId) {
        requireActiveTenantMutationContext();
        String validEmployeeId = Preconditions.requireText(employeeId, "employeeId");
        lockParentMutation(validEmployeeId);
        EmployeePosition target = requireEmployeePosition(validEmployeeId, relationId);
        validatePrimaryPositionOwner(target);
        int changed = 0;
        for (EmployeePosition current : activePrimaryPositions(validEmployeeId)) {
            if (SortAbility.sameValue(current.getId(), target.getId())) {
                continue;
            }
            current.setPrimaryPosition(Boolean.FALSE);
            changed += update(current);
        }
        if (!Boolean.TRUE.equals(target.getEnabled()) || !Boolean.TRUE.equals(target.getPrimaryPosition())) {
            target.setEnabled(Boolean.TRUE);
            target.setPrimaryPosition(Boolean.TRUE);
            changed += update(target);
        }
        return changed;
    }

    public void moveEmployeePosition(String employeeId, String relationId, String previousId, String nextId) {
        requireEmployeePosition(employeeId, relationId);
        if (previousId != null && !previousId.isBlank()) {
            requireEmployeePosition(employeeId, previousId);
            moveAfter(relationId, previousId);
            return;
        }
        if (nextId != null && !nextId.isBlank()) {
            requireEmployeePosition(employeeId, nextId);
            moveBefore(relationId, nextId);
            return;
        }
        throw new IllegalArgumentException("sort requires previousId or nextId");
    }

    private EmployeePosition requireEmployeePosition(String employeeId, String relationId) {
        String validEmployeeId = Preconditions.requireText(employeeId, "employeeId");
        String validRelationId = Preconditions.requireText(relationId, "relationId");
        EmployeePosition relation = select(validRelationId);
        if (relation == null || !SortAbility.sameValue(validEmployeeId, relation.getEmployeeId())) {
            throw BusinessExceptions.warning("iam.employee-position.not-belong-to-employee",
                    "该任职不属于当前职员");
        }
        return relation;
    }

    private Criteria employeeCriteria(String employeeId) {
        return Criteria.of().eq("employeeId", employeeId);
    }

    private List<EmployeePosition> activePrimaryPositions(String employeeId) {
        return list(employeeCriteria(employeeId)
                .eq("primaryPosition", Boolean.TRUE)
                .eq("enabled", Boolean.TRUE), new PageRequest(0, Integer.MAX_VALUE));
    }

    private boolean retiresActivePrimary(EmployeePosition existing, EmployeePosition incoming) {
        return existing != null
                && Boolean.TRUE.equals(existing.getEnabled())
                && Boolean.TRUE.equals(existing.getPrimaryPosition())
                && (!Boolean.TRUE.equals(incoming.getEnabled())
                || !Boolean.TRUE.equals(incoming.getPrimaryPosition()));
    }

    private void validatePrimaryPositionOwner(EmployeePosition relation) {
        Employee employee = employeeService.requireEnabledOrThrow(relation.getEmployeeId(), () -> BusinessExceptions.warning(
                "iam.employee-position.employee-not-active", "职员不存在或已停用"));
        if (!SortAbility.sameValue(relation.getOrganizationId(), employee.getOrganizationId())
                || !SortAbility.sameValue(relation.getDepartmentId(), employee.getDepartmentId())) {
            throw BusinessExceptions.warning("iam.employee-position.primary-owner-mismatch",
                    "主岗位的机构和部门必须与职员主机构、主部门一致");
        }
    }
}
