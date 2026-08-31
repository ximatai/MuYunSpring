package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceReadPipelineTest {
    private static final ReferenceTarget CUSTOMER = ReferenceTarget.of("demo", "customer");

    @Test
    void shouldBatchDirectProjectionsPerTargetAndPreserveManyOrder() {
        List<Map<String, Object>> records = new ArrayList<>(List.of(
                record("customerId", "customer-1", "watcherIds", List.of("customer-2", "customer-1")),
                record("customerId", "customer-2", "watcherIds", List.of("customer-2"))));
        List<List<String>> requestedIds = new ArrayList<>();
        ReferenceAbility<?> customer = new FakeReferenceAbility((ids, fields) -> {
            requestedIds.add(List.copyOf(ids));
            return Map.of(
                    "customer-1", Map.of("title", "客户一"),
                    "customer-2", Map.of("title", "客户二"));
        });
        List<ReferencePlan> plans = List.of(
                ReferencePlan.of("customerId", CUSTOMER, ReferenceCardinality.ONE).withProjection("title", "customerTitle"),
                ReferencePlan.of("watcherIds", CUSTOMER, ReferenceCardinality.MANY).withProjection("title", "watcherTitles"));

        new ReferenceReadPipeline<Map<String, Object>>(plans, List.of(), value -> value,
                Map::putAll, ignored -> customer).populate(records);

        assertThat(requestedIds).containsExactly(List.of("customer-1", "customer-2"));
        assertThat(records).extracting(item -> item.get("customerTitle"))
                .containsExactly("客户一", "客户二");
        assertThat(records.getFirst().get("watcherTitles")).isEqualTo(List.of("客户二", "客户一"));
    }

    @Test
    void shouldBatchEveryHopOfTypedReferenceLoadAcrossRecords() {
        ReferenceTarget middle = ReferenceTarget.of("demo", "middle");
        ReferenceTarget terminal = ReferenceTarget.of("demo", "terminal");
        List<Map<String, Object>> records = new ArrayList<>(List.of(
                record("middleId", "middle-1"), record("middleId", "middle-2")));
        List<List<String>> middleRequests = new ArrayList<>();
        List<List<String>> terminalRequests = new ArrayList<>();
        List<ReferenceReadObserver.ProjectionRequest> observations = new ArrayList<>();
        ReferenceAbility<?> middleAbility = new FakeReferenceAbility((ids, fields) -> {
            middleRequests.add(List.copyOf(ids));
            return Map.of("middle-1", Map.of("terminalId", "terminal-1"),
                    "middle-2", Map.of("terminalId", "terminal-2"));
        });
        ReferenceAbility<?> terminalAbility = new FakeReferenceAbility((ids, fields) -> {
            terminalRequests.add(List.copyOf(ids));
            return Map.of("terminal-1", Map.of("title", "终点一"),
                    "terminal-2", Map.of("title", "终点二"));
        });
        ReferencePlan plan = ReferencePlan.of("middleId", middle, ReferenceCardinality.ONE);
        ReferenceLoadPath path = new ReferenceLoadPath("middleId", middle,
                List.of(new ReferenceLoadPath.Hop(terminal, "terminalId")), "title", "terminalTitle");

        new ReferenceReadPipeline<Map<String, Object>>(List.of(plan), List.of(path), value -> value,
                Map::putAll, target -> target.equals(middle) ? middleAbility : terminalAbility,
                observations::add).populate(records);

        assertThat(middleRequests).containsExactly(List.of("middle-1", "middle-2"));
        assertThat(terminalRequests).containsExactly(List.of("terminal-1", "terminal-2"));
        assertThat(observations).extracting(ReferenceReadObserver.ProjectionRequest::target)
                .containsExactly(middle, terminal);
        assertThat(observations).extracting(ReferenceReadObserver.ProjectionRequest::fields)
                .containsExactly(List.of("terminalId"), List.of("title"));
        assertThat(observations).extracting(ReferenceReadObserver.ProjectionRequest::idCount)
                .containsExactly(2, 2);
        assertThat(observations).extracting(ReferenceReadObserver.ProjectionRequest::hopIndex)
                .containsExactly(0, 1);
        assertThat(records).extracting(item -> item.get("terminalTitle")).containsExactly("终点一", "终点二");
    }

    @Test
    void shouldWriteNullWhenAReferenceLoadTargetIsUnavailable() {
        List<Map<String, Object>> records = new ArrayList<>(List.of(record("customerId", "missing-customer")));
        ReferencePlan plan = ReferencePlan.of("customerId", CUSTOMER, ReferenceCardinality.ONE);
        ReferenceLoadPath path = new ReferenceLoadPath("customerId", CUSTOMER, List.of(), "title", "customerTitle");
        ReferenceAbility<?> customer = new FakeReferenceAbility((ids, fields) -> Map.of());

        new ReferenceReadPipeline<Map<String, Object>>(List.of(plan), List.of(path), value -> value,
                Map::putAll, ignored -> customer).populate(records);

        assertThat(records.getFirst()).containsEntry("customerTitle", null);
    }

    @Test
    void shouldResolveReferenceLoadWhenTheSourceUsesANonIdCandidateKey() {
        List<Map<String, Object>> records = new ArrayList<>(List.of(record("customerCode", "C-001")));
        ReferencePlan plan = ReferencePlan.of("customerCode", CUSTOMER, ReferenceCardinality.ONE)
                .withTargetFields("code", "title");
        ReferenceLoadPath path = new ReferenceLoadPath("customerCode", CUSTOMER, List.of(), "title", "customerTitle");
        ReferenceAbility<?> customer = new ReferenceAbility<Target>() {
            @Override public BaseDao<Target, String> getDao() { return null; }
            @Override public String getModuleAlias() { return "demo.customer"; }
            @Override public Map<String, String> referenceRecordIds(ReferencePlan ignored, java.util.Collection<String> values) {
                return Map.of("C-001", "customer-1");
            }
            @Override public Map<String, Map<String, Object>> projections(java.util.Collection<String> ids,
                                                                            java.util.Collection<String> fields) {
                return Map.of("customer-1", Map.of("title", "客户一"));
            }
        };

        new ReferenceReadPipeline<Map<String, Object>>(List.of(plan), List.of(path), value -> value,
                Map::putAll, ignored -> customer).populate(records);

        assertThat(records.getFirst()).containsEntry("customerTitle", "客户一");
    }

    @Test
    void shouldBatchMultiHopSelectionProjectionsThroughTheSourceIndependentTargetResolver() {
        ReferenceTarget organization = ReferenceTarget.of("tenant", "organization");
        List<List<String>> customerRequests = new ArrayList<>();
        List<List<String>> organizationRequests = new ArrayList<>();
        ReferenceAbility<?> customer = new FakeReferenceAbility((ids, fields) -> {
            customerRequests.add(List.copyOf(ids));
            return Map.of("customer-1", Map.of("organizationId", "organization-1"),
                    "customer-2", Map.of("organizationId", "organization-2"));
        });
        ReferenceAbility<?> dynamicOrganization = new FakeReferenceAbility((ids, fields) -> {
            organizationRequests.add(List.copyOf(ids));
            return Map.of("organization-1", Map.of("regionCode", "CN-31"),
                    "organization-2", Map.of("regionCode", "CN-33"));
        });
        ReferenceTargetResolver resolver = new ReferenceTargetResolver() {
            @Override
            public java.util.Optional<ReferenceAbility<?>> resolve(ReferenceTarget target) {
                return java.util.Optional.of(target.equals(CUSTOMER) ? customer : dynamicOrganization);
            }

            @Override
            public java.util.Optional<ReferencePlan> referencePlan(ReferenceTarget target, String sourceField) {
                return CUSTOMER.equals(target) && "organizationId".equals(sourceField)
                        ? java.util.Optional.of(ReferencePlan.of("organizationId", organization, ReferenceCardinality.ONE))
                        : java.util.Optional.empty();
            }
        };

        Map<String, Map<String, Object>> values = ReferenceSelectionProjectionReader.read(CUSTOMER,
                List.of("customer-1", "customer-2"),
                List.of(new ReferenceSelectionProjection("organizationId.regionCode")), resolver);

        assertThat(values).containsExactly(
                Map.entry("customer-1", Map.of("organizationId.regionCode", "CN-31")),
                Map.entry("customer-2", Map.of("organizationId.regionCode", "CN-33")));
        assertThat(customerRequests).containsExactly(List.of("customer-1", "customer-2"));
        assertThat(organizationRequests).containsExactly(List.of("organization-1", "organization-2"));
    }

    @Test
    void shouldRejectSelectionProjectionThroughANonIdCandidateKeyHop() {
        ReferenceTarget organization = ReferenceTarget.of("tenant", "organization");
        ReferenceTargetResolver resolver = new ReferenceTargetResolver() {
            @Override
            public java.util.Optional<ReferenceAbility<?>> resolve(ReferenceTarget target) {
                return java.util.Optional.of(new FakeReferenceAbility((ids, fields) -> Map.of()));
            }

            @Override
            public java.util.Optional<ReferencePlan> referencePlan(ReferenceTarget target, String sourceField) {
                return java.util.Optional.of(ReferencePlan.of("organizationCode", organization, ReferenceCardinality.ONE)
                        .withTargetFields("code", "title"));
            }
        };

        assertThatThrownBy(() -> ReferenceSelectionProjectionReader.read(CUSTOMER, List.of("customer-1"),
                List.of(new ReferenceSelectionProjection("organizationCode.regionCode")), resolver))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                .hasMessageContaining("selection projection hop requires an id-backed reference");
    }

    private static Map<String, Object> record(Object... values) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) record.put((String) values[index], values[index + 1]);
        return record;
    }

    private static final class FakeReferenceAbility implements ReferenceAbility<Target> {
        private final java.util.function.BiFunction<List<String>, List<String>, Map<String, Map<String, Object>>> loader;

        private FakeReferenceAbility(java.util.function.BiFunction<List<String>, List<String>, Map<String, Map<String, Object>>> loader) {
            this.loader = loader;
        }

        @Override public BaseDao<Target, String> getDao() { return null; }
        @Override public String getModuleAlias() { return "demo.customer"; }
        @Override public Map<String, Map<String, Object>> projections(java.util.Collection<String> ids,
                                                                        java.util.Collection<String> fields) {
            return loader.apply(List.copyOf(ids), List.copyOf(fields));
        }
    }

    private static final class Target extends StandardTitledEntity { }
}
