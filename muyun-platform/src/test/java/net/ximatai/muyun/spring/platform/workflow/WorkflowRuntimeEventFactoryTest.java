package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRuntimeEventFactoryTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WorkflowRuntimeEventFactory factory = new WorkflowRuntimeEventFactory();

    @Test
    void shouldWriteStableRouteSelectedPayload() throws Exception {
        WorkflowEvent event = factory.routeSelected(instance(), route(false), "user-1",
                Instant.parse("2026-06-05T03:00:00Z"));

        JsonNode payload = OBJECT_MAPPER.readTree(event.getPayloadText());

        assertThat(event.getEventType()).isEqualTo(WorkflowEventType.ROUTE_SELECTED);
        assertThat(payload.path("routeId").asText()).isEqualTo("route-1");
        assertThat(payload.path("routeKey").asText()).isEqualTo("route-main");
        assertThat(payload.path("branchNodeKey").asText()).isEqualTo("branch");
        assertThat(payload.path("sourceNodeKey").asText()).isEqualTo("approve");
        assertThat(payload.path("targetNodeKey").asText()).isEqualTo("next");
        assertThat(payload.path("addedByAddSign").asBoolean()).isFalse();
        assertThat(payload.path("isAddSignRoute").asBoolean()).isFalse();
        assertThat(payload.path("addSignSourceNodeKey").isNull()).isTrue();
    }

    @Test
    void shouldWriteStableRouteDroppedPayloadWithClosedReason() throws Exception {
        WorkflowRouteInstance route = route(true);
        route.setClosedReason("workflow route replaced by addSign");

        WorkflowEvent event = factory.routeDropped(instance(), route, "user-1",
                Instant.parse("2026-06-05T03:00:00Z"));

        JsonNode payload = OBJECT_MAPPER.readTree(event.getPayloadText());

        assertThat(event.getEventType()).isEqualTo(WorkflowEventType.ROUTE_DROPPED);
        assertThat(event.getPayloadText()).isNotEqualTo("workflow route replaced by addSign");
        assertThat(payload.path("routeId").asText()).isEqualTo("route-1");
        assertThat(payload.path("routeKey").asText()).isEqualTo("route-main");
        assertThat(payload.path("addedByAddSign").asBoolean()).isTrue();
        assertThat(payload.path("isAddSignRoute").asBoolean()).isTrue();
        assertThat(payload.path("addSignSourceNodeKey").asText()).isEqualTo("approve");
        assertThat(payload.path("closedReason").asText()).isEqualTo("workflow route replaced by addSign");
    }

    private WorkflowInstance instance() {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setTenantId("tenant-1");
        return instance;
    }

    private WorkflowRouteInstance route(boolean addedByAddSign) {
        WorkflowRouteInstance route = new WorkflowRouteInstance();
        route.setId("route-1");
        route.setInstanceId("instance-1");
        route.setRouteKey("route-main");
        route.setRouteRunId("route-main:run");
        route.setBranchNodeKey("branch");
        route.setSourceNodeKey("approve");
        route.setTargetNodeKey("next");
        route.setAddedByAddSign(addedByAddSign);
        route.setAddSignSourceNodeKey(addedByAddSign ? "approve" : null);
        return route;
    }
}
