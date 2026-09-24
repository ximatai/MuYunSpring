package net.ximatai.muyun.spring.starter.configuration.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.query.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.time.PlatformTimeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformQueryTimePolicyTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MuYunSpringRuntimeConfiguration.class)
            .withBean(Clock.class, () -> Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
            .withPropertyValues("muyun.platform.time.default-zone-id=Asia/Shanghai");

    @Test
    void staticAbilityAndQueryCompilerShareTheHostPolicyWithoutDynamicRuntime() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PlatformTimeService.class);
            QueryAbility<StandardEntity> ability = PlatformQueryTimePolicyTest::descriptor;
            assertThat(values(ability.queryCriteria(request(null))))
                    .containsExactly(Instant.parse("2026-03-07T16:00:00Z"), Instant.parse("2026-03-08T16:00:00Z"));
            assertThat(values(new QueryCompiler(descriptor(), QueryCriteriaComposition.TREE).criteria(request(null))))
                    .containsExactlyElementsOf(values(ability.queryCriteria(request(null))));
            // Explicit request timezone takes precedence and spans the 23-hour DST day.
            assertThat(values(ability.queryCriteria(request("America/New_York"))))
                    .containsExactly(Instant.parse("2026-03-08T05:00:00Z"), Instant.parse("2026-03-09T04:00:00Z"));
        });
    }

    @Test
    void hostSuppliedTimeServiceIsUsedByStandardQueryConstructors() {
        runner.withBean(PlatformTimeService.class, () -> new PlatformTimeService(
                Clock.systemUTC(), ZoneId.of("America/New_York"), List.of()))
                .run(context -> assertThat(values(new QueryCompiler(descriptor()).criteria(request(null))))
                        .containsExactly(Instant.parse("2026-03-08T05:00:00Z"), Instant.parse("2026-03-09T04:00:00Z")));
    }

    @Test
    void closingAnOlderHostRegistrationMustNotClearTheCurrentPolicy() throws Exception {
        PlatformTimeService older = new PlatformTimeService(Clock.systemUTC(), ZoneId.of("Asia/Shanghai"), List.of());
        PlatformTimeService current = new PlatformTimeService(Clock.systemUTC(), ZoneId.of("America/New_York"), List.of());
        try (AutoCloseable olderRegistration = PlatformAbilityRuntime.configureTimeService(() -> older)) {
            try (AutoCloseable currentRegistration = PlatformAbilityRuntime.configureTimeService(() -> current)) {
                olderRegistration.close();
                assertThat(PlatformAbilityRuntime.timeService()).isSameAs(current);
                assertThat(values(new QueryCompiler(descriptor()).criteria(request(null))))
                        .containsExactly(Instant.parse("2026-03-08T05:00:00Z"), Instant.parse("2026-03-09T04:00:00Z"));
            }
            assertThat(PlatformAbilityRuntime.timeService()).isNotSameAs(current).isNotSameAs(older);
            assertThat(PlatformAbilityRuntime.timeService().resolveZoneId(null)).isEqualTo(ZoneId.systemDefault());
        }
    }

    private static QueryDescriptor descriptor() {
        return QueryDescriptor.builder("test.time")
                .field(QueryField.of("createdAt", QueryValueType.INSTANT, QueryOperator.BETWEEN)).build();
    }

    private static QueryRequest request(String zone) {
        return new QueryRequest(List.of(new QueryCondition("createdAt", QueryOperator.BETWEEN,
                List.of("2026-03-08", "2026-03-08"), zone)), null, Map.of(), List.of(), null, null,
                Map.of(), null, List.of(), false, null);
    }

    private static List<Object> values(Criteria criteria) {
        List<Object> result = new ArrayList<>();
        collect(criteria.getRoot(), result);
        return result;
    }

    private static void collect(CriteriaGroup group, List<Object> values) {
        for (CriteriaGroup.Entry entry : group.getEntries()) {
            if (entry.getNode() instanceof CriteriaClause clause) values.addAll(clause.getValues());
            else if (entry.getNode() instanceof CriteriaGroup nested) collect(nested, values);
        }
    }
}
