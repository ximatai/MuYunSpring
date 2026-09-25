package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDelegationServiceTest {
    private final WorkflowDelegationService service = new WorkflowDelegationService(new TestMemoryDao<>());

    @Test
    void standardWritesNormalizeAndRequireInheritedTitle() {
        WorkflowDelegation record = delegation("  Delegation  ", " user-a ", " user-b ");
        service.insert(record);
        assertThat(record.getTitle()).isEqualTo("Delegation");
        assertThat(record.getPrincipalUserId()).isEqualTo("user-a");
        record.setTitle(" ");
        assertThatThrownBy(() -> service.update(record))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
        assertThatThrownBy(() -> service.insert(delegation(" ", "user-a", "user-b")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
    }

    @Test
    void shouldCreateDisabledAndRejectEnabledUpdateOrDelete() {
        WorkflowDelegation delegation = delegation("specific", "user-a", "user-b");
        delegation.setEnabled(true);
        String id = service.insert(delegation);

        WorkflowDelegation saved = service.select(id);
        assertThat(saved.getEnabled()).isFalse();

        service.enable(id);
        saved.setTitle("changed");
        assertThatThrownBy(() -> service.update(saved))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot be updated");
        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot be deleted");

        service.disable(id);
        saved.setTitle("changed");
        service.update(saved);
        assertThat(service.select(id).getTitle()).isEqualTo("changed");
        assertThat(service.delete(id)).isEqualTo(1);
    }

    @Test
    void shouldMatchMostSpecificDelegationAndKeepStableSelectionForSameDelegate() {
        String general = service.insert(delegation("general", "user-a", "user-b"));
        WorkflowDelegation module = delegation("module", "user-a", "user-b");
        module.setModuleScopeType(WorkflowDelegationScopeType.INCLUDE);
        module.setModuleAliases(Set.of("sales.contract"));
        String moduleId = service.insert(module);
        WorkflowDelegation moduleOrg = delegation("module-org", "user-a", "user-b");
        moduleOrg.setModuleScopeType(WorkflowDelegationScopeType.INCLUDE);
        moduleOrg.setModuleAliases(Set.of("sales.contract"));
        moduleOrg.setOrgScopeType(WorkflowDelegationScopeType.INCLUDE);
        moduleOrg.setOrgIds(Set.of("org-1"));
        String moduleOrgId = service.insert(moduleOrg);
        service.enable(general);
        service.enable(moduleId);
        service.enable(moduleOrgId);

        WorkflowDelegationMatch match = service.match("user-a", "sales.contract", "org-1");

        assertThat(match.delegationPolicyId()).isEqualTo(moduleOrgId);
        assertThat(match.delegateUserId()).isEqualTo("user-b");
        assertThat(match.snapshotText()).contains("\"matchedOrgId\":\"org-1\"");
    }

    @Test
    void shouldRejectStrongestMatchConflictForDifferentDelegates() {
        WorkflowDelegation first = delegation("first", "user-a", "user-b");
        first.setModuleScopeType(WorkflowDelegationScopeType.INCLUDE);
        first.setModuleAliases(Set.of("sales.contract"));
        WorkflowDelegation second = delegation("second", "user-a", "user-c");
        second.setOrgScopeType(WorkflowDelegationScopeType.INCLUDE);
        second.setOrgIds(Set.of("org-1"));
        service.enable(service.insert(first));
        service.enable(service.insert(second));

        assertThatThrownBy(() -> service.match("user-a", "sales.contract", "org-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("match conflict");
    }

    @Test
    void shouldNotMatchIncludedOrgWhenInstanceOrgIsBlank() {
        WorkflowDelegation delegation = delegation("org", "user-a", "user-b");
        delegation.setOrgScopeType(WorkflowDelegationScopeType.INCLUDE);
        delegation.setOrgIds(Set.of("org-1"));
        service.enable(service.insert(delegation));

        assertThat(service.match("user-a", "sales.contract", null)).isNull();
    }

    @Test
    void shouldReportOptimisticLockWhenEnableLosesCompareAndSetRace() {
        WorkflowDelegationService racingService = new WorkflowDelegationService(new TestMemoryDao<>() {
            @Override
            public int updateByIdAndVersion(WorkflowDelegation entity, Integer expectedVersion) {
                return 0;
            }
        });
        String id = racingService.insert(delegation("racing", "user-a", "user-b"));

        assertThatThrownBy(() -> racingService.enable(id, 0))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("workflow delegation version conflict");
    }

    private WorkflowDelegation delegation(String title, String principal, String delegate) {
        WorkflowDelegation delegation = new WorkflowDelegation();
        delegation.setTitle(title);
        delegation.setPrincipalUserId(principal);
        delegation.setDelegateUserId(delegate);
        delegation.setModuleScopeType(WorkflowDelegationScopeType.ALL);
        delegation.setOrgScopeType(WorkflowDelegationScopeType.ALL);
        return delegation;
    }
}
