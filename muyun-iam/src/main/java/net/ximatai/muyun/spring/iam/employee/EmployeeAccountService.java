package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.action.ActionMessageReporter;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.ability.action.DataChangeRecorder;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.RelatedRecordDeletion;
import net.ximatai.muyun.spring.platform.deletion.RelatedRecordDeletionService;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.util.Preconditions;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Service
public class EmployeeAccountService extends TenantActiveScopedService<EmployeeAccount> {
    public static final String MODULE_ALIAS = "iam.employee_account";

    private final EmployeeService employeeService;
    private final UserAccountService userAccountService;
    private final ActionMessageReporter actionMessageReporter;
    private final DataChangeRecorder dataChangeRecorder;
    @Autowired
    private RelatedRecordDeletionService relatedRecordDeletion;

    public EmployeeAccountService(EmployeeAccountDao employeeAccountDao,
                                  ActiveTenantVerifier activeTenantVerifier,
                                  EmployeeService employeeService,
                                  UserAccountService userAccountService) {
        this(employeeAccountDao, activeTenantVerifier, employeeService, userAccountService,
                new ActionMessageReporter(), new DataChangeRecorder());
    }

    @Autowired
    public EmployeeAccountService(EmployeeAccountDao employeeAccountDao,
                                  ActiveTenantVerifier activeTenantVerifier,
                                  EmployeeService employeeService,
                                  UserAccountService userAccountService,
                                  ActionMessageReporter actionMessageReporter,
                                  DataChangeRecorder dataChangeRecorder) {
        super(MODULE_ALIAS, EmployeeAccount.class, employeeAccountDao, activeTenantVerifier);
        this.employeeService = employeeService;
        this.userAccountService = userAccountService;
        this.actionMessageReporter = actionMessageReporter;
        this.dataChangeRecorder = dataChangeRecorder;
    }

    @Override
    public void normalizeBeforeMutation(EmployeeAccount binding) {
        binding.setEmployeeId(Preconditions.requireText(binding.getEmployeeId(), "employeeId"));
        binding.setUserId(Preconditions.requireText(binding.getUserId(), "userId"));
    }

    @Override
    protected void validateBeforeSave(EmployeeAccount binding) {
        validateAccountReferences(binding);
        rejectDuplicate(binding, Criteria.of()
                .eq("employeeId", binding.getEmployeeId()),
                "employee can bind only one user account: " + binding.getEmployeeId());
        rejectDuplicate(binding, Criteria.of().eq("userId", binding.getUserId()),
                "user account can bind only one employee: " + binding.getUserId());
    }

    public EmployeeAccount accountOfEmployee(String employeeId) {
        return list(employeeCriteria(Preconditions.requireText(employeeId, "employeeId")),
                new PageRequest(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    public String bindAccount(String employeeId, EmployeeAccount binding) {
        binding.setEmployeeId(Preconditions.requireText(employeeId, "employeeId"));
        return insert(binding);
    }

    @Transactional
    public AccountProvisionResult provisionAccount(String employeeId, UserAccount account) {
        String validEmployeeId = Preconditions.requireText(employeeId, "employeeId");
        if (accountOfEmployee(validEmployeeId) != null) {
            throw BusinessExceptions.warning("iam.employee-account.already-bound",
                    "该职员已绑定账号");
        }
        employeeService.requireEnabled(validEmployeeId, "employee is not active: " + validEmployeeId);
        UserAccount user = normalizeProvisionUser(account);
        String userId = userAccountService.insert(user);
        EmployeeAccount binding = new EmployeeAccount();
        binding.setEmployeeId(validEmployeeId);
        binding.setUserId(userId);
        String bindingId = bindAccount(validEmployeeId, binding);
        actionMessageReporter.success("iam.employee-account.provisioned", "账号已创建并绑定职员");
        dataChangeRecorder.created(UserAccountService.class, userId);
        dataChangeRecorder.created(EmployeeAccountService.class, bindingId);
        dataChangeRecorder.updated(EmployeeService.class, validEmployeeId);
        return new AccountProvisionResult(userAccountService.select(userId), select(bindingId));
    }

    @Transactional
    public int removeAccount(String employeeId) {
        String validEmployeeId = Preconditions.requireText(employeeId, "employeeId");
        if (relatedRecordDeletion == null) {
            throw new IllegalStateException("account removal requires action authorization services");
        }
        var relation = new RelatedRecordDeletion<>(employeeService, this, EmployeeAccount::getEmployeeId,
                userAccountService, EmployeeAccount::getUserId);
        var outcome = relatedRecordDeletion.delete(relation, validEmployeeId, "employeeAccounts", () -> {
            EmployeeAccount binding = accountOfEmployee(validEmployeeId);
            return binding == null ? null : binding.getId();
        });
        if (outcome.binding() == null) return 0;
        String userId = outcome.targetId();
        if (outcome.targetDeleted() == 0) {
            userAccountService.cleanupDeletedUserReferences(userId);
        }
        actionMessageReporter.success("iam.employee-account.removed", "账户已移除");
        dataChangeRecorder.deleted(EmployeeAccountService.class, outcome.binding().getId());
        dataChangeRecorder.deleted(UserAccountService.class, userId);
        dataChangeRecorder.updated(EmployeeService.class, validEmployeeId);
        return 1;
    }

    public String employeeIdOfUser(String userId) {
        EmployeeAccount binding = accountOfUser(userId);
        return binding == null ? null : binding.getEmployeeId();
    }

    public EmployeeAccount accountOfUser(String userId) {
        return list(Criteria.of().eq("userId", Preconditions.requireText(userId, "userId")),
                new PageRequest(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    public List<EmployeeAccount> accountsOfUsers(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = userIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return list(Criteria.of().in("userId", ids), new PageRequest(0, ids.size()));
    }

    private Criteria employeeCriteria(String employeeId) {
        return Criteria.of().eq("employeeId", employeeId);
    }

    private void validateAccountReferences(EmployeeAccount binding) {
        employeeService.requireEnabled(binding.getEmployeeId(),
                "employee is not active: " + binding.getEmployeeId());
        userAccountService.requireEnabled(binding.getUserId(),
                "user account is not active: " + binding.getUserId());
    }

    private UserAccount normalizeProvisionUser(UserAccount account) {
        if (account == null) {
            throw new IllegalArgumentException("account must not be null");
        }
        if (account.getEnabled() == null) {
            account.setEnabled(Boolean.TRUE);
        }
        return account;
    }

    public record AccountProvisionResult(UserAccount user, EmployeeAccount binding) {
    }
}
