package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowSubmitRequestTest {
    @Test
    void everySubmissionOptionPreservesTheExplicitDefinitionIdentity() {
        var time = Instant.parse("2026-10-21T00:00:00Z");
        var routes = List.of(new WorkflowManualRouteSelection("branch", "route", "choice"));
        var request = WorkflowSubmitRequest.workflowById("sales.contract", "record", "definition")
                .withReason("start").withOperator("user").withAuthOrgId("org").withOperatedAt(time)
                .withSelectedRoute("route", "choice").withManualRouteSelections(routes);
        assertThat(request.definitionId()).isEqualTo("definition");
        assertThat(request.definitionAlias()).isNull();
        assertThat(request.approvalRequired()).isFalse();
        assertThat(request.reason()).isEqualTo("start");
        assertThat(request.operatorId()).isEqualTo("user"); assertThat(request.authOrgId()).isEqualTo("org");
        assertThat(request.operatedAt()).isEqualTo(time); assertThat(request.manualRouteSelections()).isEqualTo(routes);
        assertThat(request.selectedRouteKey()).isEqualTo("route"); assertThat(request.selectedReason()).isEqualTo("choice");
    }

    @Test
    void legacyCanonicalConstructorAndAliasFactoryKeepTheirExistingMeaning() {
        var request = new WorkflowSubmitRequest("sales.contract", "record", false, "sync", null, "user", null,
                null, null, List.of(), "start");
        assertThat(request.definitionAlias()).isEqualTo("sync"); assertThat(request.definitionId()).isNull();
        assertThat(WorkflowSubmitRequest.workflow("sales.contract", "record", "sync").withReason("start").definitionId()).isNull();
    }
}
