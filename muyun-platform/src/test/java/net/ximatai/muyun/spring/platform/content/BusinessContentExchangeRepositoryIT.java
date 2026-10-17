package net.ximatai.muyun.spring.platform.content;

import static net.ximatai.muyun.spring.platform.exchange.RecordExchangeWorkbook.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRuntimeRefresher;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.platform.exchange.*;
import net.ximatai.muyun.spring.platform.exchange.importer.ImportDuplicateStrategy;
import net.ximatai.muyun.spring.platform.exchange.model.*;
import net.ximatai.muyun.spring.platform.exchange.reader.ExcelWorkbookParser;
import net.ximatai.muyun.spring.platform.exchange.writer.ExcelWorkbookPlanWriter;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import javax.sql.DataSource;

@SpringBootTest(
        classes = {
            BusinessContentRepositoryIT.TestApplication.class,
            BusinessContentExchangeRepositoryIT.ExchangeConfiguration.class
        })
class BusinessContentExchangeRepositoryIT extends PlatformPostgresIntegrationTest {
    @Autowired ContentFixtureDao rows;
    @Autowired BusinessContentService originals;
    @Autowired BusinessContentTranslationService translations;
    @Autowired BusinessContentQueryService queries;
    @Autowired BusinessContentSaveLifecycleListener lifecycle;
    @Autowired RecordExchangeService exchange;
    @Autowired RecordExchangeReceiptDao receiptRows;
    @Autowired RecordExchangeReceipts receipts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    @Autowired DynamicRecordService dynamics;
    @Autowired DynamicModuleRuntimeRefresher refresher;
    private TransactionTemplate tx;
    private String module;

    @BeforeEach
    void install() {
        module = "test.exchange_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        tx = new TransactionTemplate(transactions);
        PlatformAbilityRuntime.configureMutationTransactionOperator(
                new MutationTransactionOperator() {
                    public <T> T execute(java.util.function.Supplier<T> work) {
                        return tx.execute(status -> work.get());
                    }

                    public void lock(String scope, String key) {
                        new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                                .queryForObject(
                                        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                                        Object.class,
                                        scope + key);
                    }
                });
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(lifecycle);
    }

    @AfterEach
    void reset() {
        PlatformAbilityRuntime.resetMutationTransactionOperator();
        PlatformAbilityRuntime.resetEntitySaveLifecycleListener();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staticAndDynamicFilesPreserveRawHistoryAndImportOnlyPrivateTranslationDrafts(
            boolean dynamic) {
        try (var user = actor("cn");
                var tenant = TenantContext.use("exchange-a")) {
            var access = access(dynamic, false);
            var row =
                    access.save(
                            "main",
                            null,
                            null,
                            Map.of("title", "温度 80°C，12 件中 2 件不合格，暂勿发货。", "quantity", 12),
                            Map.of("title", new ContentWriteContext.SourceInput("zh-CN", null)));
            byte[] data =
                    exchange.export(
                            access,
                            List.of(row.id()),
                            new RecordExchangeService.Export(DATA, "en", "UTC"));
            var saved =
                    run(
                            access,
                            edit(
                                    data,
                                    "main",
                                    Map.of(
                                            "title",
                                            "Hold shipment: 2 of 12 samples failed at 60°C.",
                                            "quantity",
                                            "0")),
                            "en");
            assertThat(saved.updated()).isEqualTo(1);
            assertThat(saved.errorCount()).isZero();
            assertThat(access.read("main", null, row.id()).values())
                    .containsEntry("quantity", 0)
                    .containsEntry("title", "Hold shipment: 2 of 12 samples failed at 60°C.");
            var history =
                    originals.history(
                            access.ability("main"), row.id(), "title", PageRequest.of(1, 10));
            assertThat(history).hasSize(2);
            assertThat(history.getFirst().sourceLanguage()).isEqualTo("en");
            assertThat(history.getLast().sourceLanguage()).isEqualTo("zh-CN");
            assertThat(history).allMatch(item -> "cn".equals(item.authorId()));
            byte[] maintenance =
                    exchange.export(
                            access,
                            List.of(row.id()),
                            new RecordExchangeService.Export(TRANSLATIONS, "de", "UTC"));
            try (var reviewer = actor("de")) {
                var result =
                        run(
                                access,
                                edit(
                                        maintenance,
                                        "main",
                                        Map.of(
                                                TEXT,
                                                "Versand sperren: Bei 60°C sind 2 von 12 Proben"
                                                        + " durchgefallen.")),
                                null);
                assertThat(result.updated()).isEqualTo(1);
                assertThat(result.errorCount()).isZero();
                var state = access.translation("main", null, row.id(), "title", "de", null);
                assertThat(state.draft().authorId()).isEqualTo("de");
                assertThat(state.confirmed()).isNull();
                assertThat(
                                access.reading("main", null, row.id(), "de")
                                        .fields()
                                        .get("title")
                                        .originalFallback())
                        .isTrue();
                assertThat(
                                originals.history(
                                        access.ability("main"),
                                        row.id(),
                                        "title",
                                        PageRequest.of(1, 10)))
                        .hasSize(2);
            }
            byte[] reading =
                    exchange.export(
                            access,
                            List.of(row.id()),
                            new RecordExchangeService.Export(READING, "en", "UTC"));
            assertThatThrownBy(() -> exchange.preview(access, reading, command(null, null, null)))
                    .hasMessageContaining("Reading exports");
            var parsed = new ExcelWorkbookParser().parse(reading);
            assertThat(parsed.sheets().getFirst().columns())
                    .anyMatch(column -> column.fieldName().equals(reading("title")));
            try (var other = TenantContext.use("exchange-b");
                    var outsider =
                            CurrentUserContext.use(
                                    CurrentUser.tenantUser("other", "other", "exchange-b"))) {
                assertThatThrownBy(() -> exchange.preview(access, data, command(null, null, null)))
                        .hasMessageContaining("unavailable");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void correctedFailureFileRetainsExplicitOriginalLanguageWithoutSelectingItAgain(
            boolean dynamic) {
        try (var user = actor("cn");
                var tenant = TenantContext.use("exchange-a")) {
            var access = access(dynamic, false);
            var row =
                    access.save(
                            "main",
                            null,
                            null,
                            Map.of("title", "中文原文", "quantity", 12),
                            Map.of("title", new ContentWriteContext.SourceInput("zh-CN", null)));
            var exported =
                    exchange.export(
                            access,
                            List.of(row.id()),
                            new RecordExchangeService.Export(DATA, "en", "UTC"));
            var failed =
                    run(
                            access,
                            edit(
                                    exported,
                                    "main",
                                    Map.of("title", "New English original", "quantity", "bad")),
                            "en");
            assertThat(failed.updated()).isZero();
            assertThat(failed.errorCount()).isEqualTo(1);
            byte[] failureFile = Base64.getDecoder().decode(failed.errorWorkbookBase64());
            var parsed = new ExcelWorkbookParser().parse(failureFile);
            assertThat(
                            values(
                                    parsed.sheets().getFirst(),
                                    parsed.sheets().getFirst().rows().getFirst()))
                    .containsEntry(source("title"), "en");
            var result = run(access, edit(failureFile, "main", Map.of("quantity", "0")), null);
            assertThat(result.errorCount()).isZero();
            assertThat(result.updated()).isEqualTo(1);
            var history =
                    originals.history(
                            access.ability("main"), row.id(), "title", PageRequest.of(1, 10));
            assertThat(history).hasSize(2);
            assertThat(history.getFirst().sourceLanguage()).isEqualTo("en");
            assertThat(history.getLast().sourceLanguage()).isEqualTo("zh-CN");
        }
    }

    @Test
    void aggregateRetryPreservesOriginalFactsAndNormalParentFormulasAndChildTranslationIdentity() {
        try (var user = actor("cn");
                var tenant = TenantContext.use("exchange-a")) {
            var access = access(true, true);
            var parent =
                    access.save(
                            "main",
                            null,
                            null,
                            Map.of("title", "父记录", "quantity", 10),
                            Map.of("title", new ContentWriteContext.SourceInput("zh-CN", null)));
            var child =
                    access.save(
                            "line",
                            parent.id(),
                            null,
                            Map.of("title", "中文明细", "quantity", 1),
                            Map.of("title", new ContentWriteContext.SourceInput("zh-CN", null)));
            assertThat(access.read("main", null, parent.id()).values())
                    .containsEntry("title", "父记录");
            parent = access.read("main", null, parent.id());
            var exported =
                    exchange.export(
                            access,
                            List.of(parent.id()),
                            new RecordExchangeService.Export(DATA, "en", "UTC"));
            var failed = run(access, edit(exported, "line", Map.of("quantity", "bad")), null);
            assertThat(failed.updated()).isZero();
            assertThat(failed.errorCount()).isEqualTo(1);
            var retry =
                    run(
                            access,
                            edit(
                                    Base64.getDecoder().decode(failed.errorWorkbookBase64()),
                                    "line",
                                    Map.of("quantity", "2")),
                            null);
            assertThat(retry.updated()).isEqualTo(1);
            assertThat(retry.skipped()).isEqualTo(1);
            assertThat(retry.errorCount()).isZero();
            assertThat(access.read("main", null, parent.id()).version())
                    .isGreaterThan(parent.version());
            assertThat(access.read("main", null, parent.id()).values())
                    .containsEntry("quantity", 2);
            assertThat(
                            originals.history(
                                    access.ability("main"),
                                    parent.id(),
                                    "title",
                                    PageRequest.of(1, 10)))
                    .hasSize(1);
            assertThat(access.read("line", parent.id(), child.id()).values())
                    .containsEntry("quantity", 2);
            byte[] translationsFile =
                    exchange.export(
                            access,
                            List.of(parent.id()),
                            new RecordExchangeService.Export(TRANSLATIONS, "de", "UTC"));
            // A scoped translator can maintain only the detail row; no writable root context is
            // required.
            var onlyChild = keepRows(translationsFile, "main", List.of());
            var result =
                    run(
                            access,
                            edit(onlyChild, "line", Map.of(TEXT, "Deutsche Prüfposition")),
                            null);
            assertThat(result.updated()).isEqualTo(1);
            assertThat(result.errorCount()).isZero();
            assertThat(
                            access.translation("line", parent.id(), child.id(), "title", "de", null)
                                    .draft()
                                    .text())
                    .isEqualTo("Deutsche Prüfposition");
            assertThat(access.read("main", null, parent.id()).values())
                    .containsEntry("title", "父记录")
                    .containsEntry("quantity", 2);
        }
    }

    @Test
    void aggregateBusinessValidationRollsBackItsRowsWhileAnotherIndependentAggregateCommits() {
        try (var user = actor("cn");
                var tenant = TenantContext.use("exchange-a")) {
            var access = access(true, true);
            var good =
                    access.save(
                            "main",
                            null,
                            null,
                            Map.of("title", "有效单元"),
                            Map.of("title", new ContentWriteContext.SourceInput("zh-CN", null)));
            var bad =
                    access.save(
                            "main",
                            null,
                            null,
                            Map.of("title", "待修正单元"),
                            Map.of("title", new ContentWriteContext.SourceInput("zh-CN", null)));
            var goodChild =
                    access.save(
                            "line",
                            good.id(),
                            null,
                            Map.of("title", "有效明细", "quantity", 1),
                            Map.of("title", new ContentWriteContext.SourceInput("zh-CN", null)));
            var badChild =
                    access.save(
                            "line",
                            bad.id(),
                            null,
                            Map.of("title", "待修正明细", "quantity", 1),
                            Map.of("title", new ContentWriteContext.SourceInput("zh-CN", null)));
            var beforeBad = access.read("main", null, bad.id());
            byte[] file =
                    exchange.export(
                            access,
                            List.of(good.id(), bad.id()),
                            new RecordExchangeService.Export(DATA, "en", "UTC"));
            file = editWhere(file, "line", "有效明细", Map.of("quantity", "2"));
            file = editWhere(file, "line", "待修正明细", Map.of("quantity", "25"));
            var result = run(access, file, null);
            assertThat(result.updated()).isEqualTo(1);
            assertThat(result.errorCount()).isEqualTo(1);
            assertThat(result.partialSuccess()).isTrue();
            assertThat(access.read("main", null, good.id()).values()).containsEntry("quantity", 2);
            assertThat(access.read("line", good.id(), goodChild.id()).values())
                    .containsEntry("quantity", 2);
            assertThat(access.read("main", null, bad.id()).version())
                    .isEqualTo(beforeBad.version());
            assertThat(access.read("main", null, bad.id()).values()).containsEntry("quantity", 1);
            assertThat(access.read("line", bad.id(), badChild.id()).values())
                    .containsEntry("quantity", 1);
            byte[] failures = Base64.getDecoder().decode(result.errorWorkbookBase64());
            var parsed = new ExcelWorkbookParser().parse(failures);
            assertThat(parsed.sheets().getFirst().rows()).hasSize(1);
            assertThat(parsed.sheets().get(1).rows()).hasSize(1);
            assertThat(
                            values(
                                            parsed.sheets().getFirst(),
                                            parsed.sheets().getFirst().rows().getFirst())
                                    .get(CONTEXT))
                    .isEqualTo("true");
            var fixed = run(access, edit(failures, "line", Map.of("quantity", "3")), null);
            assertThat(fixed.updated()).isEqualTo(1);
            assertThat(fixed.skipped()).isEqualTo(1);
            assertThat(fixed.errorCount()).isZero();
            assertThat(access.read("main", null, bad.id()).values()).containsEntry("quantity", 3);
            assertThat(
                            originals.history(
                                    access.ability("main"),
                                    bad.id(),
                                    "title",
                                    PageRequest.of(1, 10)))
                    .hasSize(1);
        }
    }

    @Test
    void exactReferenceNameSqlSeparatesConfirmedAndMachineResultsAndRejectsStaleOriginals() {
        try (var user = actor("cn");
                var tenant = TenantContext.use("exchange-a")) {
            var access = access(false, false);
            var row =
                    access.save(
                            "main",
                            null,
                            null,
                            Map.of("title", "数控设备"),
                            Map.of("title", new ContentWriteContext.SourceInput("zh-CN", null)));
            var state = access.translation("main", null, row.id(), "title", "en", null);
            var draft =
                    access.translation(
                            "main",
                            null,
                            row.id(),
                            "title",
                            "en",
                            new BusinessContentTranslationService.Change(
                                    state.original().revisionId(), 1, null, "CNC Equipment", null));
            translations.confirm(
                    access.ability("main"),
                    BusinessContentService.Location.record(row.id()),
                    "title",
                    "en",
                    new BusinessContentTranslationService.Change(
                            state.original().revisionId(),
                            1,
                            draft.expectedRevision(),
                            null,
                            draft.draft().revision()));
            assertThat(
                            access.ability("main")
                                    .count(
                                            queries.referenceName(
                                                    access.ability("main"),
                                                    "title",
                                                    "CNC Equipment",
                                                    "en",
                                                    "CONFIRMED")))
                    .isEqualTo(1);
            assertThat(
                            access.ability("main")
                                    .count(
                                            queries.referenceName(
                                                    access.ability("main"),
                                                    "title",
                                                    "CNC Equipment",
                                                    "en",
                                                    "MACHINE")))
                    .isZero();
            access.save(
                    "main",
                    null,
                    access.read("main", null, row.id()),
                    Map.of("title", "新版设备"),
                    Map.of("title", new ContentWriteContext.SourceInput("zh-CN", 1)));
            assertThat(
                            access.ability("main")
                                    .count(
                                            queries.referenceName(
                                                    access.ability("main"),
                                                    "title",
                                                    "CNC Equipment",
                                                    "en",
                                                    "CONFIRMED")))
                    .isZero();
        }
    }

    private Access access(boolean dynamic, boolean children) {
        if (dynamic) {
            var fields =
                    List.of(
                            FieldDefinition.string("title", "标题")
                                    .content(
                                            new ContentFieldDefinition(
                                                    "name", ContentPolicy.MANUAL_TRANSLATIONS)),
                            FieldDefinition.integer("quantity", "数量"));
            var entities = new ArrayList<EntityDefinition>();
            entities.add(
                    new EntityDefinition(
                            "main",
                            "exchange_main_" + module.substring(module.lastIndexOf('_') + 1),
                            "Main",
                            fields));
            if (children)
                entities.add(
                        new EntityDefinition(
                                "line",
                                "exchange_line_" + module.substring(module.lastIndexOf('_') + 1),
                                "Line",
                                List.of(
                                        fields.get(0),
                                        fields.get(1),
                                        FieldDefinition.string("parentId", "Parent")
                                                .column("parent_id"))));
            if (children)
                entities.set(
                        0,
                        entities.getFirst()
                                .withFormulaRules(
                                        EntityFormulaRuleDefinition.calculation(
                                                "sumQuantity",
                                                "{quantity} = SUM({lines.quantity})"),
                                        EntityFormulaRuleDefinition.validation(
                                                "quantityLimit",
                                                "quantity",
                                                "{quantity} <= 20",
                                                "Quantity must not exceed 20")));
            var builder = ModuleDefinition.builder(module, "Exchange").entities(entities);
            if (children)
                builder.relations(
                        List.of(
                                EntityRelationDefinition.child(
                                        "lines", "main", "line", "parentId")));
            refresher.refresh(builder.build());
        }
        return new Access(dynamic, children);
    }

    private RecordExchangeService.Result run(Access access, byte[] bytes, String sourceLanguage) {
        var preview = exchange.preview(access, bytes, command(null, null, sourceLanguage));
        return exchange.execute(
                access,
                bytes,
                command(preview.documentId(), preview.previewDigest(), sourceLanguage));
    }

    private RecordExchangeService.Command command(
            String document, String digest, String sourceLanguage) {
        return new RecordExchangeService.Command(
                document,
                digest,
                List.of(
                        new RecordExchangeService.SheetCommand(
                                "main", "title", ImportDuplicateStrategy.OVERWRITE),
                        new RecordExchangeService.SheetCommand(
                                "line", "title", ImportDuplicateStrategy.OVERWRITE)),
                List.of(),
                sourceLanguage);
    }

    private byte[] edit(byte[] bytes, String entity, Map<String, String> changed) {
        return editWhere(bytes, entity, null, changed);
    }

    private byte[] editWhere(
            byte[] bytes, String entity, String matchingTitle, Map<String, String> changed) {
        var source = new ExcelWorkbookParser().parse(bytes);
        return new ExcelWorkbookPlanWriter()
                .writeToBytes(
                        new ExcelWorkbookPlan(
                                source.meta(),
                                source.sheets().stream()
                                        .map(
                                                sheet ->
                                                        new ExcelSheetPlan(
                                                                sheet.sheetName(),
                                                                sheet.entityAlias(),
                                                                sheet.entityAlias().equals("main"),
                                                                sheet.columns().stream()
                                                                        .map(
                                                                                column ->
                                                                                        new ExcelColumnPlan(
                                                                                                column
                                                                                                        .fieldName(),
                                                                                                column
                                                                                                        .title()))
                                                                        .toList(),
                                                                sheet.rows().stream()
                                                                        .map(
                                                                                row ->
                                                                                        sheet
                                                                                                .columns()
                                                                                                .stream()
                                                                                                .map(
                                                                                                        column ->
                                                                                                                (Object)
                                                                                                                        (sheet.entityAlias()
                                                                                                                                                .equals(
                                                                                                                                                        entity)
                                                                                                                                        && (matchingTitle
                                                                                                                                                        == null
                                                                                                                                                || matchingTitle
                                                                                                                                                        .equals(
                                                                                                                                                                RecordExchangeWorkbook
                                                                                                                                                                        .values(
                                                                                                                                                                                sheet,
                                                                                                                                                                                row)
                                                                                                                                                                        .get(
                                                                                                                                                                                "title")))
                                                                                                                                        && changed
                                                                                                                                                .containsKey(
                                                                                                                                                        column
                                                                                                                                                                .fieldName())
                                                                                                                                ? changed
                                                                                                                                        .get(
                                                                                                                                                column
                                                                                                                                                        .fieldName())
                                                                                                                                : row
                                                                                                                                        .get(
                                                                                                                                                column
                                                                                                                                                        .columnIndex())))
                                                                                                .toList())
                                                                        .toList()))
                                        .toList()));
    }

    private byte[] keepRows(byte[] bytes, String entity, List<List<Object>> rows) {
        var source = new ExcelWorkbookParser().parse(bytes);
        return new ExcelWorkbookPlanWriter()
                .writeToBytes(
                        new ExcelWorkbookPlan(
                                source.meta(),
                                source.sheets().stream()
                                        .map(
                                                sheet ->
                                                        new ExcelSheetPlan(
                                                                sheet.sheetName(),
                                                                sheet.entityAlias(),
                                                                sheet.entityAlias().equals("main"),
                                                                sheet.columns().stream()
                                                                        .map(
                                                                                column ->
                                                                                        new ExcelColumnPlan(
                                                                                                column
                                                                                                        .fieldName(),
                                                                                                column
                                                                                                        .title()))
                                                                        .toList(),
                                                                sheet.entityAlias().equals(entity)
                                                                        ? rows
                                                                        : sheet.rows().stream()
                                                                                .map(
                                                                                        row ->
                                                                                                row
                                                                                                        .stream()
                                                                                                        .map(
                                                                                                                value ->
                                                                                                                        (Object)
                                                                                                                                value)
                                                                                                        .toList())
                                                                                .toList()))
                                        .toList()));
    }

    private CurrentUserContext.Scope actor(String id) {
        return CurrentUserContext.use(CurrentUser.tenantUser(id, id, "exchange-a"));
    }

    private final class StaticService implements CrudAbility<ContentFixture> {
        public BaseDao<ContentFixture, String> getDao() {
            return rows;
        }

        public String getModuleAlias() {
            return module;
        }

        public Class<?> modelClass() {
            return ContentFixture.class;
        }
    }

    private final class Access implements RecordExchangeAccess {
        private final boolean dynamic, children;
        private final StaticService statics = new StaticService();

        Access(boolean dynamic, boolean children) {
            this.dynamic = dynamic;
            this.children = children;
        }

        CrudAbility<?> ability(String entity) {
            return dynamic ? dynamics.entity(module, entity) : statics;
        }

        public RecordExchangeDefinition definition() {
            var fields =
                    List.of(
                            new RecordExchangeDefinition.Field(
                                    "title",
                                    "Title",
                                    FieldType.STRING,
                                    false,
                                    true,
                                    false,
                                    true,
                                    false),
                            new RecordExchangeDefinition.Field(
                                    "quantity",
                                    "Quantity",
                                    FieldType.INTEGER,
                                    false,
                                    true,
                                    false,
                                    false,
                                    false));
            var entities = new ArrayList<RecordExchangeDefinition.Entity>();
            entities.add(new RecordExchangeDefinition.Entity("main", "Main", null, null, fields));
            if (children)
                entities.add(
                        new RecordExchangeDefinition.Entity(
                                "line", "Line", "lines", "parentId", fields));
            return new RecordExchangeDefinition(module, entities);
        }

        public String inputValue(String entity, String field, String value, String language) {
            return value;
        }

        public Object outputValue(String entity, String field, Object value, String language) {
            return value;
        }

        public Row read(String entity, String parent, String id) {
            var value = ability(entity).select(id);
            if (value == null
                    || parent != null
                            && !parent.equals(((DynamicRecord) value).getValue("parentId")))
                throw new IllegalArgumentException("unavailable");
            return row(value);
        }

        private Row row(EntityContract value) {
            Map<String, Object> fields = new LinkedHashMap<>();
            if (value instanceof DynamicRecord record) fields.putAll(record.getValues());
            else {
                fields.put("title", ((ContentFixture) value).getTitle());
                fields.put("quantity", ((ContentFixture) value).getQuantity());
            }
            return new Row(value.getId(), value.getVersion(), value.getCreatedAt(), fields);
        }

        public List<Row> match(String entity, String parent, String field, Object value) {
            var criteria = Criteria.of().eq(field, value);
            if (parent != null) criteria.eq("parentId", parent);
            return ability(entity).list(criteria, PageRequest.of(1, 2)).stream()
                    .map(this::row)
                    .toList();
        }

        public List<Row> children(String entity, String parent, int limit) {
            return dynamics
                    .aggregateChildrenForView(
                            module, parent, "lines", null, PageRequest.of(1, limit))
                    .stream()
                    .map(this::row)
                    .toList();
        }

        public Row save(
                String entity,
                String parent,
                Row existing,
                Map<String, Object> changes,
                Map<String, ContentWriteContext.SourceInput> languages) {
            return tx.execute(
                    status -> {
                        if (dynamic) {
                            var record =
                                    existing == null
                                            ? dynamics.newRecord(module, entity)
                                            : dynamics.select(module, entity, existing.id()).copy();
                            record.getChildren()
                                    .keySet()
                                    .forEach(code -> record.setChildren(code, null));
                            changes.forEach(record::setValue);
                            if (existing != null) record.setVersion(existing.version());
                            try (var source = ContentWriteContext.open(record, languages)) {
                                if (parent != null) {
                                    var root = read("main", null, parent);
                                    return saveAggregate(
                                                    new AggregateChange(
                                                            "parent", "main", root, Map.of(),
                                                            Map.of(), "CONTEXT"),
                                                    List.of(
                                                            new AggregateChange(
                                                                    "child",
                                                                    entity,
                                                                    existing,
                                                                    changes,
                                                                    languages,
                                                                    existing == null
                                                                            ? "CREATE"
                                                                            : "UPDATE")))
                                            .get("child");
                                } else if (existing == null)
                                    dynamics.create(module, entity, record);
                                else dynamics.update(module, entity, record);
                            }
                            return read(entity, parent, record.getId());
                        }
                        var record =
                                existing == null
                                        ? new ContentFixture()
                                        : statics.select(existing.id());
                        if (existing != null) record.setVersion(existing.version());
                        if (changes.containsKey("title"))
                            record.setTitle((String) changes.get("title"));
                        if (changes.containsKey("quantity"))
                            record.setQuantity((Integer) changes.get("quantity"));
                        try (var source = ContentWriteContext.open(record, languages)) {
                            if (existing == null) statics.insert(record);
                            else statics.update(record);
                        }
                        return read(entity, parent, record.getId());
                    });
        }

        @Override
        public Map<String, Row> saveAggregate(
                AggregateChange root, List<AggregateChange> changedChildren) {
            if (changedChildren.isEmpty())
                return RecordExchangeAccess.super.saveAggregate(root, changedChildren);
            return tx.execute(
                    status -> {
                        var draft =
                                root.existing() == null
                                        ? dynamics.newRecord(module, "main")
                                        : dynamics.select(module, "main", root.existing().id())
                                                .copy();
                        draft.getChildren().keySet().forEach(code -> draft.setChildren(code, null));
                        root.changes().forEach(draft::setValue);
                        if (root.existing() != null) draft.setVersion(root.existing().version());
                        draft.contentLanguageInputs(root.languages());
                        var pending = new LinkedHashMap<String, DynamicRecord>();
                        for (var change : changedChildren) {
                            var child =
                                    change.existing() == null
                                            ? dynamics.newRecord(module, "line")
                                            : dynamics.select(
                                                            module, "line", change.existing().id())
                                                    .copy();
                            change.changes().forEach(child::setValue);
                            if (change.existing() != null)
                                child.setVersion(change.existing().version());
                            child.contentLanguageInputs(change.languages());
                            pending.put(change.key(), child);
                        }
                        draft.setPartialChildren("lines", List.copyOf(pending.values()));
                        try (var source = ContentWriteContext.open(draft, root.languages())) {
                            if (root.existing() == null) dynamics.create(module, "main", draft);
                            else dynamics.update(module, "main", draft);
                        }
                        var saved = new LinkedHashMap<String, Row>();
                        saved.put(root.key(), read("main", null, draft.getId()));
                        pending.forEach(
                                (key, child) ->
                                        saved.put(key, read("line", draft.getId(), child.getId())));
                        return saved;
                    });
        }

        private BusinessContentService.Location location(String entity, String parent, String id) {
            return parent == null
                    ? BusinessContentService.Location.record(id)
                    : new BusinessContentService.Location(parent, "lines", id);
        }

        public BusinessContentService.Sources sources(String entity, String parent, String id) {
            return parent == null
                    ? originals.sources(ability(entity), id)
                    : originals.childSources(ability("main"), parent, "lines", id);
        }

        public BusinessContentTranslationService.Reading reading(
                String entity, String parent, String id, String language) {
            return translations.read(ability("main"), location(entity, parent, id), language);
        }

        public BusinessContentTranslationService.Maintenance translation(
                String entity,
                String parent,
                String id,
                String field,
                String language,
                BusinessContentTranslationService.Change change) {
            return change == null
                    ? translations.prepare(
                            ability("main"),
                            location(entity, parent, id),
                            field,
                            language,
                            net.ximatai.muyun.spring.common.platform.PlatformAction.CONTENT_EDIT)
                    : translations.saveDraft(
                            ability("main"), location(entity, parent, id), field, language, change);
        }

        public Reference resolve(
                String entity,
                String parent,
                String field,
                String input,
                String language,
                Map<String, Object> values) {
            throw new UnsupportedOperationException();
        }

        public String referenceLabel(
                String entity,
                String parent,
                String field,
                Object ids,
                String language,
                Map<String, Object> values) {
            throw new UnsupportedOperationException();
        }

        public void requireReference(
                String entity,
                String parent,
                String field,
                Object ids,
                Map<String, Object> values) {
            throw new UnsupportedOperationException();
        }
    }

    @TestConfiguration
    @EnableMuYunRepositories(basePackageClasses = RecordExchangeReceiptDao.class)
    @Import({RecordExchangeReceipts.class, RecordExchangeService.class})
    static class ExchangeConfiguration {
        @Bean
        ObjectMapper mapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        DynamicRecordRuntime runtime(IDatabaseOperations<?> database) {
            return DynamicRecordRuntime.builder(database).build();
        }

        @Bean
        DynamicSchemaService schema(IDatabaseOperations<?> database) {
            return new DynamicSchemaService(database);
        }

        @Bean
        DynamicModuleRuntimeRefresher refresher(
                DynamicSchemaService schema, DynamicRecordRuntime runtime) {
            return new DynamicModuleRuntimeRefresher(schema, runtime);
        }

        @Bean
        DynamicRecordService records(DynamicRecordRuntime runtime) {
            return new DynamicRecordService(runtime);
        }
    }
}
