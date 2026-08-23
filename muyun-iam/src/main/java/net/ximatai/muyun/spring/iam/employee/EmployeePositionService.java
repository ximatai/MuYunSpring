package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.TenantStandardBusinessService;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.util.Preconditions;
import net.ximatai.muyun.spring.iam.department.Department;
import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.position.Position;
import net.ximatai.muyun.spring.iam.position.PositionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EmployeePositionService extends TenantStandardBusinessService<EmployeePosition> implements
        SoftDeleteAbility<EmployeePosition>,
        EnableAbility<EmployeePosition>,
        SortAbility<EmployeePosition>,
        ChildAbility<EmployeePosition> {
    public static final String MODULE_ALIAS = "iam.employee_position";

    private final EmployeeService employeeService;
    private final OrganizationService organizationService;
    private final DepartmentService departmentService;
    private final PositionService positionService;

    @Autowired
    public EmployeePositionService(EmployeePositionDao employeePositionDao,
                                   ActiveTenantVerifier activeTenantVerifier,
                                   EmployeeService employeeService,
                                   OrganizationService organizationService,
                                   DepartmentService departmentService,
                                   PositionService positionService) {
        super(MODULE_ALIAS, EmployeePosition.class, employeePositionDao, activeTenantVerifier);
        this.employeeService = employeeService;
        this.organizationService = organizationService;
        this.departmentService = departmentService;
        this.positionService = positionService;
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
        Employee employee = validatePositionReferences(relation);
        if (Boolean.TRUE.equals(relation.getPrimaryPosition())) {
            validatePrimaryPositionOwner(relation, employee);
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


    public List<EmployeePosition> positions(String employeeId) {
        String validEmployeeId = Preconditions.requireText(employeeId, "employeeId");
        return list(employeeCriteria(validEmployeeId), new PageRequest(0, Integer.MAX_VALUE),
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
        EmployeePosition target = requireEmployeePosition(validEmployeeId, relationId);
        validatePrimaryPositionOwner(target, validatePositionReferences(target));
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

    private Employee validatePositionReferences(EmployeePosition relation) {
        Employee employee = employeeService.requireEnabledOrThrow(relation.getEmployeeId(), () -> BusinessExceptions.warning(
                "iam.employee-position.employee-not-active", "职员不存在或已停用"));
        Organization organization = organizationService.requireEnabledOrThrow(relation.getOrganizationId(), () -> BusinessExceptions.warning(
                "iam.employee-position.organization-not-active", "任职机构不存在或已停用"));
        Department department = departmentService.requireEnabledOrThrow(relation.getDepartmentId(), () -> BusinessExceptions.warning(
                "iam.employee-position.department-not-active", "任职部门不存在或已停用"));
        Position position = positionService.requireEnabledOrThrow(relation.getPositionId(), () -> BusinessExceptions.warning(
                "iam.employee-position.position-not-active", "任职岗位不存在或已停用"));
        requireSameTenant(employee, organization, "机构");
        requireSameTenant(employee, department, "部门");
        requireSameTenant(employee, position, "岗位");
        if (!SortAbility.sameValue(relation.getOrganizationId(), department.getOrganizationId())) {
            throw BusinessExceptions.warning("iam.employee-position.department-organization-mismatch",
                    "任职所属部门必须隶属于同一机构");
        }
        return employee;
    }

    private void requireSameTenant(Employee employee,
                                   net.ximatai.muyun.spring.common.model.contract.EntityContract referenced,
                                   String referenceLabel) {
        if (SortAbility.sameValue(employee.getTenantId(), referenced.getTenantId())) {
            return;
        }
        throw BusinessExceptions.warning("iam.employee-position.cross-tenant-reference",
                "任职职员与" + referenceLabel + "必须属于同一租户");
    }

    private void validatePrimaryPositionOwner(EmployeePosition relation, Employee employee) {
        if (!SortAbility.sameValue(relation.getOrganizationId(), employee.getOrganizationId())
                || !SortAbility.sameValue(relation.getDepartmentId(), employee.getDepartmentId())) {
            throw BusinessExceptions.warning("iam.employee-position.primary-owner-mismatch",
                    "主岗位的机构和部门必须与职员主机构、主部门一致");
        }
    }
}
