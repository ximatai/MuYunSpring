package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.JdbiConfigurer;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = CodeRepositoryIT.TestApplication.class)
class CodeRepositoryIT extends PlatformPostgresIntegrationTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    private final CodeRuleService ruleService;
    private final CodeGenerateService generateService;
    private final CodeSequenceStateService stateService;
    private final CodeLedgerEntryService ledgerService;
    private final CodeRecycleEntryService recycleService;
    private final CodeIssueLogService issueLogService;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    CodeRepositoryIT(CodeRuleService ruleService,
                     CodeGenerateService generateService,
                     CodeSequenceStateService stateService,
                     CodeLedgerEntryService ledgerService,
                     CodeRecycleEntryService recycleService,
                     CodeIssueLogService issueLogService,
                     PlatformTransactionManager transactionManager) {
        this.ruleService = ruleService;
        this.generateService = generateService;
        this.stateService = stateService;
        this.ledgerService = ledgerService;
        this.recycleService = recycleService;
        this.issueLogService = issueLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    void shouldPersistCodeRuleTreeAndLifecycleRecordsThroughRepository() {
        CodeRule rule = rule(uniqueModuleAlias(), "orderNo");
        rule.setAllowRecycle(Boolean.TRUE);
        CodeRule saved = ruleService.saveRuleTree(rule);

        CodeRule viewed = ruleService.viewRuleTree(saved.getId());
        assertThat(viewed.getSegments()).hasSize(2);
        assertThat(viewed.getSequencePolicy().getSequenceLength()).isEqualTo(4);

        GenerateCodeResult generated = generateService.generate(new GenerateCodeCommand(
                rule.getModuleAlias(),
                "main",
                null,
                "orderNo",
                null,
                LocalDateTime.parse("2026-06-08T10:00:00"),
                Map.of(),
                null
        ));
        assertThat(issueLogService.selectByRuleId(saved.getId(), 10)).singleElement()
                .satisfies(log -> {
                    assertThat(log.getStatus()).isEqualTo(CodeIssueLogStatus.SUCCESS);
                    assertThat(log.getGeneratedValue()).isEqualTo("SO-0001");
                });
        ledgerService.upsertActiveBinding(rule, generated.value(), generated.basisKey(), generated.periodKey(), "order-1");
        recycleService.record(rule, generated.basisKey(), generated.periodKey(), generated.value(), "order-1");

        CodeSequenceState state = stateService.selectState(rule.getId(), generated.basisKey(), generated.periodKey());
        assertThat(state.getCurrentValue()).isEqualTo(1L);
        assertThat(ledgerService.findByRuleAndValue(rule.getId(), generated.value()).getStatus())
                .isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(recycleService.list(Criteria.of().eq("ruleId", rule.getId()), PageRequest.of(1, 10)))
                .extracting(CodeRecycleEntry::getRecycledValue)
                .containsExactly(generated.value());
    }

    @Test
    void shouldRollbackSequenceAllocationWithOuterTransaction() {
        CodeRule rule = rule(uniqueModuleAlias(), "orderNo");
        ruleService.saveRuleTree(rule);

        transactionTemplate.executeWithoutResult(status -> {
            GenerateCodeResult generated = generateService.generate(new GenerateCodeCommand(
                    rule.getModuleAlias(),
                    "main",
                    null,
                    "orderNo",
                    null,
                    LocalDateTime.parse("2026-06-08T10:00:00"),
                    Map.of(),
                    null
            ));
        assertThat(generated.value()).isEqualTo("SO-0001");
            assertThat(stateService.selectState(rule.getId(), generated.basisKey(), generated.periodKey()))
                    .isNotNull();
            status.setRollbackOnly();
        });

        assertThat(stateService.selectState(rule.getId(), CodeSequenceState.DEFAULT_BUCKET, CodeSequenceState.DEFAULT_BUCKET))
                .isNull();
        assertThat(generateService.generate(new GenerateCodeCommand(
                rule.getModuleAlias(),
                "main",
                null,
                "orderNo",
                null,
                LocalDateTime.parse("2026-06-08T10:00:00"),
                Map.of(),
                null
        )).value()).isEqualTo("SO-0001");
    }

    @Test
    void shouldAllocateSequenceAtomicallyUnderConcurrentRepositoryWrites() throws Exception {
        CodeRule rule = rule(uniqueModuleAlias(), "orderNo");
        ruleService.saveRuleTree(rule);
        int count = 24;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(count);
        try {
            List<Callable<String>> tasks = IntStream.range(0, count)
                    .mapToObj(i -> (Callable<String>) () -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        try (TenantContext.Scope ignored = TenantContext.system("code sequence concurrency test")) {
                            return generateService.generate(new GenerateCodeCommand(
                                    rule.getModuleAlias(),
                                    "main",
                                    null,
                                    "orderNo",
                                    null,
                                    LocalDateTime.parse("2026-06-08T10:00:00"),
                                    Map.of(),
                                    null
                            )).value();
                        }
                    })
                    .toList();
            var futures = tasks.stream().map(executor::submit).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> values = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    })
                    .sorted()
                    .toList();

            assertThat(values).hasSize(count).doesNotHaveDuplicates();
            assertThat(values).containsExactlyElementsOf(IntStream.rangeClosed(1, count)
                    .mapToObj(i -> "SO-%04d".formatted(i))
                    .toList());
        } finally {
            executor.shutdownNow();
        }
        try (TenantContext.Scope ignored = TenantContext.system("code sequence concurrency test")) {
            assertThat(stateService.selectState(rule.getId(), CodeSequenceState.DEFAULT_BUCKET, CodeSequenceState.DEFAULT_BUCKET)
                    .getCurrentValue()).isEqualTo(count);
        }
    }

    @Test
    void shouldTreatLedgerAsSingleOccupationFact() {
        CodeRule rule = rule(uniqueModuleAlias(), "orderNo");
        ruleService.saveRuleTree(rule);

        ledgerService.upsertActiveBinding(rule, "SO-0001", CodeSequenceState.DEFAULT_BUCKET,
                CodeSequenceState.DEFAULT_BUCKET, "order-1");

        assertThatThrownBy(() -> ledgerService.upsertActiveBinding(rule, "SO-0001",
                CodeSequenceState.DEFAULT_BUCKET, CodeSequenceState.DEFAULT_BUCKET, "order-2"))
                .hasMessageContaining("already occupied");

        ledgerService.upsertInactiveBinding(rule, "SO-0001", CodeSequenceState.DEFAULT_BUCKET,
                CodeSequenceState.DEFAULT_BUCKET, "order-1", CodeLedgerStatus.AVAILABLE,
                CodeLedgerAction.RELEASED_BY_DELETE);
        ledgerService.upsertActiveBinding(rule, "SO-0001", CodeSequenceState.DEFAULT_BUCKET,
                CodeSequenceState.DEFAULT_BUCKET, "order-2");

        CodeLedgerEntry rebound = ledgerService.findByRuleAndValue(rule.getId(), "SO-0001");
        assertThat(rebound.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(rebound.getSourceRecordId()).isEqualTo("order-2");
    }

    @Test
    void shouldConsumeRecycleAtomicallyUnderConcurrentRepositoryWrites() throws Exception {
        CodeRule rule = rule(uniqueModuleAlias(), "orderNo");
        rule.setAllowRecycle(Boolean.TRUE);
        ruleService.saveRuleTree(rule);
        recycleService.record(rule, CodeSequenceState.DEFAULT_BUCKET, CodeSequenceState.DEFAULT_BUCKET,
                "SO-0001", "order-1");

        int count = 12;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(count);
        try {
            List<Callable<CodeRecycleEntry>> tasks = IntStream.range(0, count)
                    .mapToObj(i -> (Callable<CodeRecycleEntry>) () -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        return recycleService.consumeAvailable(rule.getId(), CodeSequenceState.DEFAULT_BUCKET,
                                CodeSequenceState.DEFAULT_BUCKET);
                    })
                    .toList();
            var futures = tasks.stream().map(executor::submit).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<CodeRecycleEntry> consumed = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    })
                    .filter(entry -> entry != null)
                    .toList();

            assertThat(consumed).hasSize(1);
            assertThat(consumed.getFirst().getRecycledValue()).isEqualTo("SO-0001");
            assertThat(recycleService.list(Criteria.of()
                    .eq("ruleId", rule.getId())
                    .eq("recycledValue", "SO-0001"), PageRequest.of(1, 1))
                    .getFirst()
                    .getStatus()).isEqualTo(CodeRecycleStatus.USED);
        } finally {
            executor.shutdownNow();
        }
    }

    private CodeRule rule(String moduleAlias, String fieldName) {
        CodeRule rule = new CodeRule();
        rule.setModuleAlias(moduleAlias);
        rule.setEntityAlias("main");
        rule.setFieldName(fieldName);
        rule.setTitle(fieldName);
        rule.setFieldRole(CodeFieldRole.PRIMARY);
        rule.setMode(CodeMode.AUTO);
        rule.setEnabled(Boolean.TRUE);
        rule.setSegments(List.of(
                segment(CodeSegmentType.CONSTANT, "SO-", null),
                sequenceSegment()
        ));
        CodeSequencePolicy policy = new CodeSequencePolicy();
        policy.setStartValue(1L);
        policy.setStepValue(1L);
        policy.setSequenceLength(4);
        policy.setResetPolicy(CodeSequenceResetPolicy.NONE);
        rule.setSequencePolicy(policy);
        return rule;
    }

    private CodeRuleSegment sequenceSegment() {
        CodeRuleSegment segment = segment(CodeSegmentType.SEQUENCE, null, null);
        segment.setLength(4);
        return segment;
    }

    private CodeRuleSegment segment(CodeSegmentType type, String fixedValue, String sourceRef) {
        CodeRuleSegment segment = new CodeRuleSegment();
        segment.setSegmentType(type);
        segment.setFixedValue(fixedValue);
        segment.setSourceRef(sourceRef);
        return segment;
    }

    private String uniqueModuleAlias() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "crm.code_" + suffix;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = CodeRuleDao.class)
    static class TestApplication {
        @Bean
        DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url(postgres.getJdbcUrl())
                    .username(postgres.getUsername())
                    .password(postgres.getPassword())
                    .driverClassName(postgres.getDriverClassName())
                    .build();
        }

        @Bean
        CodeRuleService codeRuleService(CodeRuleDao ruleDao,
                                        CodeRuleSegmentService segmentService,
                                        CodeSequencePolicyService sequencePolicyService,
                                        CodeValueMappingService mappingService) {
            return new CodeRuleService(ruleDao, segmentService, sequencePolicyService, mappingService);
        }

        @Bean
        CodeRuleSegmentService codeRuleSegmentService(CodeRuleSegmentDao segmentDao) {
            return new CodeRuleSegmentService(segmentDao);
        }

        @Bean
        CodeSequencePolicyService codeSequencePolicyService(CodeSequencePolicyDao policyDao) {
            return new CodeSequencePolicyService(policyDao);
        }

        @Bean
        CodeValueMappingService codeValueMappingService(CodeValueMappingDao mappingDao) {
            return new CodeValueMappingService(mappingDao);
        }

        @Bean
        CodeSequenceAllocator codeSequenceAllocator(Jdbi jdbi) {
            return new PostgresCodeSequenceAllocator(jdbi);
        }

        @Bean
        CodeRecycleConsumer codeRecycleConsumer(Jdbi jdbi) {
            return new PostgresCodeRecycleConsumer(jdbi);
        }

        @Bean
        CodeSequenceStateService codeSequenceStateService(CodeSequenceStateDao stateDao,
                                                          CodeSequenceAllocator sequenceAllocator) {
            return new CodeSequenceStateService(stateDao, List.of(sequenceAllocator));
        }

        @Bean
        CodeLedgerEntryService codeLedgerEntryService(CodeLedgerEntryDao ledgerEntryDao) {
            return new CodeLedgerEntryService(ledgerEntryDao);
        }

        @Bean
        CodeRecycleEntryService codeRecycleEntryService(CodeRecycleEntryDao recycleEntryDao,
                                                        CodeRecycleConsumer recycleConsumer) {
            return new CodeRecycleEntryService(recycleEntryDao, List.of(recycleConsumer));
        }

        @Bean
        CodeIssueLogService codeIssueLogService(CodeIssueLogDao issueLogDao) {
            return new CodeIssueLogService(issueLogDao);
        }

        @Bean
        CodePreviewService codePreviewService() {
            return new CodePreviewService();
        }

        @Bean
        CodeGenerateService codeGenerateService(CodeRuleService ruleService,
                                                CodePreviewService previewService,
                                                CodeSequenceStateService stateService,
                                                CodeRecycleEntryService recycleEntryService,
                                                CodeIssueLogService issueLogService) {
            return new CodeGenerateService(ruleService, previewService, stateService, recycleEntryService,
                    issueLogService, null, null);
        }

        @Bean
        JdbiConfigurer bigIntegerJdbiConfigurer() {
            return jdbi -> jdbi.registerArgument(new AbstractArgumentFactory<BigInteger>(Types.BIGINT) {
                @Override
                protected Argument build(BigInteger value, ConfigRegistry config) {
                    return (position, statement, context) -> statement.setLong(position, value.longValueExact());
                }
            });
        }
    }
}
