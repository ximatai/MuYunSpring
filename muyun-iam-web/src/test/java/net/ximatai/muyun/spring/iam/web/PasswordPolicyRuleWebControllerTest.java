package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyRule;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyRuleDao;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyRuleService;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyScopeType;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordPolicyRuleWebControllerTest {
    @Test
    void shouldAuthorizeTheRuleSnapshotThroughTheStandardQueryAction() throws NoSuchMethodException {
        ActionEndpoint endpoint = PasswordPolicyRuleWebController.class
                .getMethod("activeGlobalRuleSnapshots")
                .getAnnotation(ActionEndpoint.class);

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.value()).isEqualTo(PlatformAction.QUERY);
    }

    @Test
    void shouldExposeTheServiceFallbackWhenNoGlobalRuleIsEnabled() {
        PasswordPolicyRuleDao dao = mock(PasswordPolicyRuleDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of());
        PasswordPolicyRuleWebController controller = new PasswordPolicyRuleWebController();
        ReflectionTestUtils.setField(controller, "service", new PasswordPolicyRuleService(dao));

        assertThat(controller.activeGlobalRuleSnapshots()).containsExactly(
                new PasswordPolicyPreviewRuleSnapshot(
                        null, "密码长度", "^.{6,}$", "密码长度不能少于 6 位", true, "global", 10));
    }

    @Test
    void shouldProjectTheServiceAuthoritativeRulesWithoutEditOnlyFields() {
        PasswordPolicyRuleService service = mock(PasswordPolicyRuleService.class);
        PasswordPolicyRuleWebController controller = new PasswordPolicyRuleWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        PasswordPolicyRule rule = new PasswordPolicyRule();
        rule.setId("rule-1");
        rule.setTitle("必须包含大写字母");
        rule.setPattern(".*[A-Z].*");
        rule.setMessage("必须包含大写字母");
        rule.setEnabled(Boolean.TRUE);
        rule.setScopeType(PasswordPolicyScopeType.GLOBAL);
        rule.setSortOrder(20);
        rule.setScopeId("must-not-be-projected");
        when(service.activeGlobalRules()).thenReturn(List.of(rule));

        assertThat(controller.activeGlobalRuleSnapshots()).containsExactly(
                new PasswordPolicyPreviewRuleSnapshot(
                        "rule-1", "必须包含大写字母", ".*[A-Z].*", "必须包含大写字母",
                        true, "global", 20));
        verify(service).activeGlobalRules();
    }
}
