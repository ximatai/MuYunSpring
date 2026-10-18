package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceReadPipelineTest {
    private static final ReferenceTarget CUSTOMER = ReferenceTarget.of("demo", "customer");

    @AfterEach void resetTextListener() { PlatformAbilityRuntime.resetReferenceTextReadListener(); }

    @Test void capturesAuthorizedTextIdentitiesWithoutReplacingRawValuesOrAddingTargetReads() {
        var owner = record("id", "order", "customerIds", List.of("second", "hidden", "first"));
        var facts = new ArrayList<ReferenceTextReadListener.Projection>();
        PlatformAbilityRuntime.configureReferenceTextReadListener(facts::add);
        var calls = new ArrayList<List<String>>();
        var ability = new FakeReferenceAbility((ids, fields) -> {
            calls.add(ids);
            return Map.of("first", Map.of("title", "同名", "amount", 12),
                    "second", Map.of("title", "同名", "amount", 7));
        });
        var plan = ReferencePlan.of("customerIds", CUSTOMER, ReferenceCardinality.MANY)
                .withProjection("title", "customerTitles").withProjection("amount", "amounts");
        new ReferenceReadPipeline<Map<String, Object>>(List.of(plan), List.of(), value -> value,
                Map::putAll, ignored -> ability).populate(List.of(owner));
        assertThat(calls).containsExactly(List.of("second", "hidden", "first"));
        assertThat(owner).containsEntry("customerTitles", List.of("同名", "同名"))
                .containsEntry("amounts", List.of(7, 12));
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().sourceIds()).containsExactly("second", "hidden", "first");
        assertThat(facts.getFirst().texts()).extracting(ReferenceTextReadListener.Text::recordId)
                .containsExactly("second", "first");
        assertThat(facts.getFirst().texts()).extracting(ReferenceTextReadListener.Text::originalText)
                .containsExactly("同名", "同名");
        assertThat(facts.getFirst().collection()).isTrue();
    }

    @Test void capturesTheActualTerminalIdentityAndSuppressesInaccessibleTargets() {
        var terminal = ReferenceTarget.of("demo", "terminal");
        var owner = record("id", "order", "customerId", "customer");
        var facts = new ArrayList<ReferenceTextReadListener.Projection>();
        PlatformAbilityRuntime.configureReferenceTextReadListener(facts::add);
        var customer = new FakeReferenceAbility((ids, fields) -> Map.of("customer", Map.of("terminalId", "terminal")));
        var target = new FakeReferenceAbility((ids, fields) -> Map.of("terminal", Map.of("title", "终点原文")));
        var path = new ReferenceLoadPath("customerId", CUSTOMER,
                List.of(new ReferenceLoadPath.Hop(terminal, "terminalId")), "title", "terminalTitle");
        var plan = ReferencePlan.of("customerId", CUSTOMER, ReferenceCardinality.ONE);
        new ReferenceReadPipeline<Map<String, Object>>(List.of(plan), List.of(path), value -> value, Map::putAll,
                identity -> identity.equals(CUSTOMER) ? customer : target).populate(List.of(owner));
        assertThat(owner).containsEntry("terminalTitle", "终点原文");
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().texts()).containsExactly(new ReferenceTextReadListener.Text(terminal, "terminal", "title", "终点原文"));
        facts.clear();
        var hidden = new FakeReferenceAbility((ids, fields) -> Map.of());
        var resolved = ReferenceLoadReader.readResolvedAll(path, List.of("customer"),
                identity -> identity.equals(CUSTOMER) ? customer : hidden, ReferenceReadObserver.NONE);
        assertThat(resolved).containsEntry("customer", null);
        assertThat(ReferenceLoadReader.readAll(path, List.of("customer"),
                identity -> identity.equals(CUSTOMER) ? customer : hidden)).containsEntry("customer", null);
        new ReferenceReadPipeline<Map<String, Object>>(List.of(plan), List.of(path), value -> value, Map::putAll,
                identity -> identity.equals(CUSTOMER) ? customer : hidden).populate(List.of(owner));
        assertThat(owner).containsEntry("terminalTitle", null);
        assertThat(facts).isEmpty();
    }

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
    void shouldResolveReferenceLoadByRecordIdWhenThePickerUsesANonIdCandidateKey() {
        List<Map<String, Object>> records = new ArrayList<>(List.of(record("customerId", "customer-1")));
        ReferencePlan plan = ReferencePlan.of("customerId", CUSTOMER, ReferenceCardinality.ONE)
                .withTargetFields("code", "title");
        ReferenceLoadPath path = new ReferenceLoadPath("customerId", CUSTOMER, List.of(), "title", "customerTitle");
        ReferenceAbility<?> customer = new ReferenceAbility<Target>() {
            @Override public BaseDao<Target, String> getDao() { return null; }
            @Override public String getModuleAlias() { return "demo.customer"; }
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
