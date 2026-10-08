package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowParticipantPolicyCodecTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    @Test
    void shouldParseLegacyAndJsonUserPolicies() {
        assertThat(WorkflowParticipantPolicyCodec.parse("user:approver-1", "approve").userIds())
                .containsExactly("approver-1");
        assertThat(WorkflowParticipantPolicyCodec.parse("approver-1, approver-2", "approve").userIds())
                .containsExactly("approver-1", "approver-2");
        assertThat(WorkflowParticipantPolicyCodec.parse("""
                {"userIds":["approver-1","approver-2"]}
                """, "approve").userIds())
                .containsExactly("approver-1", "approver-2");
        assertThat(WorkflowParticipantPolicyCodec.parse("""
                {"rules":[{"type":"USER","targetId":"approver-1"}]}
                """, "approve").userIds())
                .containsExactly("approver-1");
        assertThat(WorkflowParticipantPolicyCodec.parse("""
                ["approver-1"]
                """, "approve").userIds())
                .containsExactly("approver-1");
    }

    @Test
    void shouldResolveIdentityPoliciesThroughAdaptersAndRejectUnsupportedLegacySyntax() {
        assertThatThrownBy(() -> WorkflowParticipantPolicyCodec.parse("""
                {"rules":[{"type":"ROLE","targetId":"finance"}]}
                """, "approve"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("identity resolution");
        assertThatThrownBy(() -> WorkflowParticipantPolicyCodec.parse("role:finance", "approve"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unsupported workflow participant policy");
    }

    @Test
    void shouldRequireSingleUserForCurrentRuntimeBoundary() {
        WorkflowParticipantPolicyCodec.ParticipantPolicy policy = WorkflowParticipantPolicyCodec.parse(
                "{\"userIds\":[\"approver-1\",\"approver-2\"]}", "approve");

        assertThatThrownBy(() -> policy.requireSingleUser("empty", "multi"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("multi");
    }
}
