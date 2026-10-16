package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowParticipantPolicyCodecTest {
    @Test
    void parsesUserPoliciesIntoTheRuntimeRuleContract() {
        for (String text : new String[]{
                "user:approver-1", "approver-1",
                "{\"userIds\":[\"approver-1\"]}",
                "{\"rules\":[{\"type\":\"USER\",\"targetId\":\"approver-1\"}]}",
                "[\"approver-1\"]"}) {
            assertThat(WorkflowParticipantPolicyCodec.rules(text, "approve"))
                    .singleElement().satisfies(rule -> {
                        assertThat(rule.type()).isEqualTo("USER");
                        assertThat(rule.ids()).containsExactly("approver-1");
                    });
        }
        assertThat(WorkflowParticipantPolicyCodec.rules("approver-1, approver-2", "approve"))
                .singleElement().satisfies(rule -> assertThat(rule.ids()).containsExactly("approver-1", "approver-2"));
    }

    @Test
    void retainsIdentitySourcesForTheIdentityResolver() {
        assertThat(WorkflowParticipantPolicyCodec.rules(
                "{\"rules\":[{\"type\":\"ROLE\",\"ids\":[\"finance\"]}]}", "approve"))
                .singleElement().satisfies(rule -> {
                    assertThat(rule.type()).isEqualTo("ROLE");
                    assertThat(rule.ids()).containsExactly("finance");
                });
        assertThatThrownBy(() -> WorkflowParticipantPolicyCodec.rules("role:finance", "approve"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unsupported workflow participant policy");
    }
}
