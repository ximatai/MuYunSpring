package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class PasswordPolicyRuleServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installMutationHost() {
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.configureMutationTransactionOperator(
                new net.ximatai.muyun.spring.ability.MutationTransactionOperator() {
                    @Override public <T> T execute(java.util.function.Supplier<T> work) { return work.get(); }
                    @Override public void lock(String scope, String key) { }
                });
    }

    @org.junit.jupiter.api.AfterEach
    void resetMutationHost() {
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.resetMutationTransactionOperator();
    }

    @Test
    void initializationPreservesOperatorChangesToDefaultComplexity() {
        PasswordPolicyRuleDao dao = mock(PasswordPolicyRuleDao.class);
        PasswordPolicyRuleService service = new PasswordPolicyRuleService(dao);
        PasswordPolicyRule existing = service.initialData().getFirst();
        existing.setPattern("^.{12,}$");
        existing.setMessage("Minimum twelve characters");
        when(dao.findById(existing.getId())).thenReturn(existing);
        assertThat(service.initialDataOptions().order()).isLessThan(50);
        assertThat(service.initialDataOptions().policy())
                .isEqualTo(net.ximatai.muyun.spring.ability.initialdata.InitialDataPolicy.CREATE_IF_MISSING);
        new net.ximatai.muyun.spring.platform.initialdata.InitialDataExecutor(List.of(service), List.of())
                .initializeAll();
        org.mockito.Mockito.verify(dao, org.mockito.Mockito.never()).updateById(any());
        assertThat(existing.getPattern()).isEqualTo("^.{12,}$");
    }

    @Test
    void shouldFallbackToDefaultMinLengthRuleWhenNoRuleConfigured() {
        PasswordPolicyRuleDao dao = mock(PasswordPolicyRuleDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of());
        PasswordPolicyRuleService service = new PasswordPolicyRuleService(dao);

        service.validatePassword("secret1");

        assertThatThrownBy(() -> service.validatePassword("12345"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("密码长度不能少于 6 位")
                .satisfies(error -> assertThat(((BusinessException) error).actionMessage().code())
                        .isEqualTo("iam.user.password-policy-violated"))
                .satisfies(error -> assertThat(((BusinessException) error).actionMessage().textSource().sourceId())
                        .isEqualTo("iam.password-policy.fallback.message"));
    }

    @Test
    void shouldKeepInitialDataIdsWithinStandardEntityLength() {
        PasswordPolicyRuleDao dao = mock(PasswordPolicyRuleDao.class);
        PasswordPolicyRuleService service = new PasswordPolicyRuleService(dao);

        assertThat(service.initialData())
                .extracting(PasswordPolicyRule::getId)
                .allSatisfy(id -> assertThat(id.length()).isLessThanOrEqualTo(PlatformAbilityFields.TREE_PARENT_LENGTH));
    }

    @Test
    void shouldValidateEnabledRulesByRegexAndMessage() {
        PasswordPolicyRuleDao dao = mock(PasswordPolicyRuleDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of(
                rule("密码必须包含数字", "^.*\\d.*$", "密码必须包含数字", 20)
        ));
        PasswordPolicyRuleService service = new PasswordPolicyRuleService(dao);

        service.validatePassword("secret1");

        assertThatThrownBy(() -> service.validatePassword("secret"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("密码必须包含数字");
    }

    @Test
    void configuredPolicyFailureRetainsTheActualRuleMessageAndVersion() {
        var dao = mock(PasswordPolicyRuleDao.class);
        var configured = rule("必须包含数字", ".*\\d.*", "密码必须包含数字", 20);
        configured.setId("custom-policy");
        when(dao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of(configured));
        var service = new PasswordPolicyRuleService(dao);
        assertThatThrownBy(() -> service.validatePassword("letters"))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("iam.user.password-policy-violated");
                    assertThat(failure.actionMessage().textSource()).isEqualTo(PasswordPolicyTextSources.message(configured));
                    assertThat(failure.actionMessage().textSource().sourceKind())
                            .isEqualTo(net.ximatai.muyun.spring.common.i18n.TextReference.SourceKind.CONFIG);
                    assertThat(failure.getMessage()).isEqualTo(configured.getMessage());
                });
    }

    @Test
    void shouldNormalizeGlobalRuleAndRejectInvalidRegex() {
        PasswordPolicyRuleDao dao = mock(PasswordPolicyRuleDao.class);
        PasswordPolicyRuleService service = spy(new PasswordPolicyRuleService(dao));
        when(dao.insert(any())).thenReturn("rule-1");
        PasswordPolicyRule rule = new PasswordPolicyRule();
        rule.setTitle(" 包含大写字母 ");
        rule.setPattern("^.*[A-Z].*$");
        rule.setMessage(" 密码必须包含大写字母 ");

        // Global (platform) rules are maintained from a system scope only.
        try (TenantContext.Scope ignored = TenantContext.system("password policy rule test")) {
            service.insert(rule);

            assertThat(rule.getScopeType()).isEqualTo(PasswordPolicyScopeType.GLOBAL);
            assertThat(rule.getScopeId()).isNull();
            assertThat(rule.getScopeKey()).isEqualTo("global:");
            assertThat(rule.getTitle()).isEqualTo("包含大写字母");
            assertThat(rule.getMessage()).isEqualTo("密码必须包含大写字母");

            rule.setId("rule-1");
            doReturn(rule).when(service).selectExistingForScopedMutation(any());
            rule.setPattern("[");
            assertThatThrownBy(() -> service.update(rule))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("invalid password policy regex");
        }
    }

    @Test
    void shouldRejectBlankPolicyFieldsThroughStandardInsert() {
        PasswordPolicyRuleService service = new PasswordPolicyRuleService(mock(PasswordPolicyRuleDao.class));
        PasswordPolicyRule missingTitle = rule(null, ".+", "Invalid password", 10);
        PasswordPolicyRule missingPattern = rule("Policy", null, "Invalid password", 10);
        PasswordPolicyRule missingMessage = rule("Policy", ".+", "  ", 10);
        try (TenantContext.Scope ignored = TenantContext.system("password policy rule test")) {
            assertThatThrownBy(() -> service.insert(missingTitle))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
            assertThatThrownBy(() -> service.insert(missingPattern))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pattern");
            assertThatThrownBy(() -> service.insert(missingMessage))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("message");
        }
    }

    private PasswordPolicyRule rule(String title, String pattern, String message, int sortOrder) {
        PasswordPolicyRule rule = new PasswordPolicyRule();
        rule.setScopeType(PasswordPolicyScopeType.GLOBAL);
        rule.setScopeKey("global:");
        rule.setTitle(title);
        rule.setPattern(pattern);
        rule.setMessage(message);
        rule.setEnabled(Boolean.TRUE);
        rule.setSortOrder(sortOrder);
        return rule;
    }
}
