package net.ximatai.muyun.spring.platform.exchange;

import static net.ximatai.muyun.spring.platform.exchange.RecordExchangeWorkbook.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.content.ContentWriteContext.SourceInput;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.platform.content.*;
import net.ximatai.muyun.spring.platform.exchange.importer.ImportDuplicateStrategy;
import net.ximatai.muyun.spring.platform.exchange.model.*;
import net.ximatai.muyun.spring.platform.exchange.reader.ExcelWorkbookParser;
import net.ximatai.muyun.spring.platform.exchange.writer.ExcelWorkbookPlanWriter;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

/**
 * Real workbook protocol and orchestration. PostgreSQL producer contracts separately cover storage
 * and rollback.
 */
class RecordExchangeServiceTest {
    private final Map<String, RecordExchangeReceipt> persisted = new HashMap<>();
    private final Fixture access = new Fixture();
    private RecordExchangeService service;
    private CurrentUserContext.Scope actor;
    private TenantContext.Scope tenant;

    @BeforeEach
    void setup() {
        var dao = mock(RecordExchangeReceiptDao.class);
        when(dao.findById(anyString())).thenAnswer(call -> persisted.get(call.getArgument(0)));
        when(dao.insert(any()))
                .thenAnswer(
                        call -> {
                            var row = (RecordExchangeReceipt) call.getArgument(0);
                            persisted.put(row.getId(), row);
                            return row.getId();
                        });
        when(dao.updateByIdAndVersion(any(), any())).thenReturn(1);
        var manager =
                new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                    protected Object doGetTransaction() {
                        return new Object();
                    }

                    protected void doBegin(
                            Object transaction,
                            org.springframework.transaction.TransactionDefinition definition) {}

                    protected void doCommit(
                            org.springframework.transaction.support.DefaultTransactionStatus
                                    status) {}

                    protected void doRollback(
                            org.springframework.transaction.support.DefaultTransactionStatus
                                    status) {}
                };
        service =
                new RecordExchangeService(
                        new RecordExchangeReceipts(dao),
                        new ObjectMapper().findAndRegisterModules(),
                        manager);
        PlatformAbilityRuntime.configureMutationTransactionOperator(
                new MutationTransactionOperator() {
                    public <T> T execute(java.util.function.Supplier<T> work) {
                        return work.get();
                    }

                    public void lock(String namespace, String key) {}
                });
        actor = CurrentUserContext.use(CurrentUser.tenantUser("cn", "CN", "tenant-a"));
        tenant = TenantContext.use("tenant-a");
    }

    @AfterEach
    void cleanup() {
        tenant.close();
        actor.close();
        PlatformAbilityRuntime.configureMutationTransactionOperator(null);
    }

    @Test
    void
            ninetySuccessfulRowsAreExcludedFromTheCorrectableTenRowFailureFileAndOriginalFileReplayIsIdempotent() {
        byte[] file =
                managed(
                        file(
                                List.of("code", "qty"),
                                IntStream.range(0, 100)
                                        .mapToObj(
                                                index ->
                                                        List.<Object>of(
                                                                "R-" + index,
                                                                "C-" + index,
                                                                index < 90 ? "1" : "bad"))
                                        .toList(),
                                List.of()));
        var first = run(file, ImportDuplicateStrategy.ERROR);
        assertThat(first.created()).isEqualTo(90);
        assertThat(first.errorCount()).isEqualTo(10);
        assertThat(first.rows().stream().filter(row -> "ERROR".equals(row.operation())))
                .allMatch(row -> row.problem().contains("qty"));
        byte[] failures = Base64.getDecoder().decode(first.errorWorkbookBase64());
        var parsed = new ExcelWorkbookParser().parse(failures);
        assertThat(parsed.sheets().getFirst().rows()).hasSize(10);
        var corrected = edit(failures, "main", "qty", "2");
        var retried = run(corrected, ImportDuplicateStrategy.ERROR);
        assertThat(retried.created()).isEqualTo(10);
        assertThat(retried.errorCount()).isZero();
        assertThat(access.rows).hasSize(100);
        var replayed = run(file, ImportDuplicateStrategy.ERROR);
        assertThat(replayed.created()).isZero();
        assertThat(replayed.skipped()).isEqualTo(90);
    }

    @Test
    void permissionRejectionsDoNotDiscloseInternalScopeDiagnostics() {
        var row = access.put("main", null, Map.of("code", "KEEP", "qty", 12));
        byte[] exported =
                service.export(
                        access,
                        List.of(row.id()),
                        new RecordExchangeService.Export(DATA, "en", "UTC"));
        access.denyReads = true;
        var preview =
                service.preview(
                        access,
                        exported,
                        command(null, null, ImportDuplicateStrategy.OVERWRITE, List.of()));
        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().getFirst().problem())
                .contains("Current permissions")
                .doesNotContain("private-scope-marker");
        assertThat(preview.rows().getFirst().currentValues()).isEmpty();
    }

    @Test
    void omittedMiddleColumnCannotShiftOtherFieldsOrTheirVerifiedRowIdentity() throws Exception {
        var existing = access.put("main", null, Map.of("code", "KEEP", "qty", 12));
        byte[] exported =
                service.export(
                        access,
                        List.of(existing.id()),
                        new RecordExchangeService.Export(DATA, "en", "UTC"));
        byte[] modified;
        try (var workbook =
                        new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                                new java.io.ByteArrayInputStream(exported));
                var bytes = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            for (var cell : sheet.getRow(0)) {
                String field = cell.getStringCellValue();
                if (field.equals("main.code")) {
                    int index = cell.getColumnIndex();
                    sheet.getRow(0).getCell(index).setBlank();
                    sheet.getRow(1).getCell(index).setBlank();
                    sheet.getRow(2).getCell(index).setBlank();
                } else if (field.equals("main.qty"))
                    sheet.getRow(2).getCell(cell.getColumnIndex()).setCellValue(0);
            }
            workbook.write(bytes);
            modified = bytes.toByteArray();
        }
        var result = run(modified, ImportDuplicateStrategy.OVERWRITE);
        assertThat(result.errorCount()).isZero();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(access.rows.get(existing.id()).values())
                .containsEntry("code", "KEEP")
                .containsEntry("qty", 0);
    }

    @Test
    void failureFileRetainsNameResolutionLanguageForDirectRetry() {
        byte[] bytes =
                file(
                        List.of("code", "status", "qty"),
                        List.of(List.of("R-de", "C-de", "Offen", "bad")),
                        List.of());
        var base = command(null, null, ImportDuplicateStrategy.ERROR, List.of());
        var explicit =
                new RecordExchangeService.Command(null, null, base.sheets(), List.of(), null, "de");
        var preview = service.preview(access, bytes, explicit);
        var failed =
                service.execute(
                        access,
                        bytes,
                        new RecordExchangeService.Command(
                                preview.documentId(),
                                preview.previewDigest(),
                                base.sheets(),
                                List.of(),
                                null,
                                "de"));
        assertThat(failed.errorCount()).isEqualTo(1);
        byte[] retryFile = Base64.getDecoder().decode(failed.errorWorkbookBase64());
        assertThat(new ExcelWorkbookParser().parse(retryFile).meta().language()).isEqualTo("de");
        var retry = run(edit(retryFile, "main", "qty", "0"), ImportDuplicateStrategy.ERROR);
        assertThat(retry.errorCount()).isZero();
        assertThat(retry.created()).isEqualTo(1);
        assertThat(access.rows.values().iterator().next().values()).containsEntry("status", "open");
    }

    @Test
    void failedAggregateIsRetriedAsOneBusinessUnitWithoutCommittingAPartialParent() {
        byte[] file =
                file(
                        List.of("code"),
                        List.of(List.of("R-1", "parent")),
                        List.of(List.of("R-1", "ok", "1"), List.of("R-1", "fix", "bad")));
        var first = run(file, ImportDuplicateStrategy.ERROR);
        assertThat(first.created()).isZero();
        assertThat(first.errorCount()).isEqualTo(3);
        assertThat(access.rows).isEmpty();
        byte[] failures = Base64.getDecoder().decode(first.errorWorkbookBase64());
        var parsed = new ExcelWorkbookParser().parse(failures);
        assertThat(parsed.sheets().get(1).rows()).hasSize(2);
        var parent =
                values(parsed.sheets().getFirst(), parsed.sheets().getFirst().rows().getFirst());
        assertThat(parent.get(CONTEXT)).isNull();
        assertThat(parent.get("code")).isEqualTo("parent");
        int writes = access.saves;
        var retry = run(edit(failures, "line", "qty", "3"), ImportDuplicateStrategy.ERROR);
        assertThat(retry.created()).isEqualTo(3);
        assertThat(retry.skipped()).isZero();
        assertThat(retry.errorCount()).isZero();
        assertThat(access.saves - writes).isEqualTo(3);
        assertThat(access.rows).hasSize(3);
    }

    @Test
    void
            blanksAndMissingFieldsPreserveExistingValuesWhileZeroFalseAndExplicitClearAreRealChanges() {
        var row =
                access.put(
                        "main",
                        null,
                        Map.of(
                                "code",
                                "old",
                                "description",
                                "中文原文",
                                "qty",
                                8,
                                "enabled",
                                true,
                                "remark",
                                "preserve"));
        byte[] file =
                file(
                        List.of("code", "description", "qty", "enabled", CLEAR),
                        List.of(Arrays.asList("R", "old", null, "0", "false", "remark")),
                        List.of());
        var result = run(file, ImportDuplicateStrategy.OVERWRITE);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(access.rows.get(row.id()).values())
                .containsEntry("description", "中文原文")
                .containsEntry("qty", 0)
                .containsEntry("enabled", false)
                .containsEntry("remark", null);
        assertThat(access.lastLanguages).isEmpty();
    }

    @Test
    void
            ordinaryBusinessAndConfirmedNameResolveUniquelyButMachineNamesNeedExplicitObjectConfirmation() {
        access.names.put(
                "CNC Equipment",
                List.of(
                        new RecordExchangeAccess.Candidate(
                                "category-a", "数控设备", "CNC Equipment", "CONFIRMED")));
        byte[] confirmed =
                file(
                        List.of("code", "category"),
                        List.of(List.of("R", "confirmed", "CNC Equipment")),
                        List.of());
        assertThat(run(confirmed, ImportDuplicateStrategy.ERROR).created()).isEqualTo(1);
        assertThat(access.rows.values().iterator().next().values())
                .containsEntry("category", "category-a");
        access.names.put(
                "Machine CNC",
                List.of(
                        new RecordExchangeAccess.Candidate(
                                "category-b", "其他设备", "Machine CNC", "MACHINE")));
        byte[] machine =
                file(
                        List.of("code", "category"),
                        List.of(List.of("M", "machine", "Machine CNC")),
                        List.of());
        var initial =
                service.preview(
                        access,
                        machine,
                        command(null, null, ImportDuplicateStrategy.ERROR, List.of()));
        assertThat(initial.rows().getFirst().choices()).hasSize(1);
        var without =
                service.execute(
                        access,
                        machine,
                        command(
                                initial.documentId(),
                                initial.previewDigest(),
                                ImportDuplicateStrategy.ERROR,
                                List.of()));
        assertThat(without.created()).isZero();
        assertThat(without.errorCount()).isEqualTo(1);
        var choice =
                new RecordExchangeService.Confirmation(
                        initial.rows().getFirst().row(), "category", "Machine CNC", "category-b");
        var preview =
                service.preview(
                        access,
                        machine,
                        command(
                                initial.documentId(),
                                null,
                                ImportDuplicateStrategy.ERROR,
                                List.of(choice)));
        var saved =
                service.execute(
                        access,
                        machine,
                        command(
                                preview.documentId(),
                                preview.previewDigest(),
                                ImportDuplicateStrategy.ERROR,
                                List.of(choice)));
        assertThat(saved.created()).isEqualTo(1);
        assertThat(saved.errorCount()).isZero();
    }

    @Test
    void exportBaselineRetainsTheSameReferenceAfterRenameButAnEditedNameIsResolvedAgain() {
        var row =
                access.put(
                        "main",
                        null,
                        Map.of("code", "exported", "category", "category-a", "qty", 1));
        access.labels.put("category-a", "旧分类");
        byte[] exported =
                service.export(
                        access,
                        List.of(row.id()),
                        new RecordExchangeService.Export(DATA, "en", "UTC"));
        access.labels.put("category-a", "Renamed category");
        var quantity = run(edit(exported, "main", "qty", "2"), ImportDuplicateStrategy.ERROR);
        assertThat(quantity.updated()).isEqualTo(1);
        assertThat(quantity.errorCount()).isZero();
        assertThat(access.rows.get(row.id()).values()).containsEntry("category", "category-a");
        byte[] fresh =
                service.export(
                        access,
                        List.of(row.id()),
                        new RecordExchangeService.Export(DATA, "en", "UTC"));
        access.names.put(
                "Other category",
                List.of(
                        new RecordExchangeAccess.Candidate(
                                "category-b", "其他分类", "Other category", "CONFIRMED")));
        var changed =
                run(
                        edit(fresh, "main", "category", "Other category"),
                        ImportDuplicateStrategy.ERROR);
        assertThat(changed.errorCount()).isZero();
        assertThat(access.rows.get(row.id()).values()).containsEntry("category", "category-b");
    }

    @Test
    void manualFileReviewFreezesTheMatchedVersionAndChangedOptionsRequireNewReview() {
        var row = access.put("main", null, Map.of("code", "existing", "qty", 1));
        byte[] file =
                file(List.of("code", "qty"), List.of(List.of("R", "existing", "2")), List.of());
        var preview =
                service.preview(
                        access,
                        file,
                        command(null, null, ImportDuplicateStrategy.OVERWRITE, List.of()));
        access.rows.put(
                row.id(),
                new RecordExchangeAccess.Row(
                        row.id(),
                        row.version() + 1,
                        row.createdAt(),
                        Map.of("code", "existing", "qty", 9)));
        var result =
                service.execute(
                        access,
                        file,
                        command(
                                preview.documentId(),
                                preview.previewDigest(),
                                ImportDuplicateStrategy.OVERWRITE,
                                List.of()));
        assertThat(result.updated()).isZero();
        assertThat(result.errorCount()).isEqualTo(1);
        assertThat(access.rows.get(row.id()).values()).containsEntry("qty", 9);
        assertThatThrownBy(
                        () ->
                                service.execute(
                                        access,
                                        file,
                                        command(
                                                preview.documentId(),
                                                preview.previewDigest(),
                                                ImportDuplicateStrategy.SKIP,
                                                List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("options changed");
    }

    @Test
    void generalNumericCellsDoNotRoundBusinessValuesOrInvalidateUntouchedReceipts() {
        for (String amount :
                List.of(
                        "1234567890.12345",
                        "123456789012345",
                        "123456789012",
                        "0.00000000012345")) {
            var row =
                    access.put(
                            "main",
                            null,
                            Map.of(
                                    "code",
                                    amount,
                                    "serial",
                                    123456789012345L,
                                    "amount",
                                    new java.math.BigDecimal(amount)));
            byte[] file =
                    service.export(
                            access,
                            List.of(row.id()),
                            new RecordExchangeService.Export(DATA, "en", "UTC"));
            var parsed = new ExcelWorkbookParser(true).parse(file);
            var values =
                    RecordExchangeWorkbook.values(
                            parsed.sheets().getFirst(),
                            parsed.sheets().getFirst().rows().getFirst());
            assertThat(values).containsEntry("serial", "123456789012345");
            assertThat(new java.math.BigDecimal(values.get("amount"))).isEqualByComparingTo(amount);
            var result = run(file, ImportDuplicateStrategy.ERROR);
            assertThat(result.errorCount()).isZero();
            assertThat(result.updated()).isZero();
        }
        assertThat(access.saves).isZero();
    }

    @Test
    void exactTextAndLargeNumbersSurviveExportAndReimportWithoutBusinessMutation() {
        String text = "  Original line one\nSecond line  ";
        var row =
                access.put(
                        "main",
                        null,
                        Map.of(
                                "code",
                                "precise",
                                "description",
                                text,
                                "serial",
                                9007199254740993L,
                                "amount",
                                new java.math.BigDecimal("1234567890.123456789")));
        byte[] file =
                service.export(
                        access,
                        List.of(row.id()),
                        new RecordExchangeService.Export(DATA, "en", "UTC"));
        var parsed = new ExcelWorkbookParser(true).parse(file);
        var values =
                RecordExchangeWorkbook.values(
                        parsed.sheets().getFirst(), parsed.sheets().getFirst().rows().getFirst());
        assertThat(values)
                .containsEntry("description", text)
                .containsEntry("serial", "9007199254740993")
                .containsEntry("amount", "1234567890.123456789");
        var result = run(file, ImportDuplicateStrategy.ERROR);
        assertThat(result.errorCount()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(access.saves).isZero();
        String revised = "  Revised English\nKeep this spacing  ";
        var revision =
                run(
                        edit(
                                service.export(
                                        access,
                                        List.of(row.id()),
                                        new RecordExchangeService.Export(DATA, "en", "UTC")),
                                "main",
                                "description",
                                revised),
                        ImportDuplicateStrategy.ERROR);
        assertThat(revision.updated()).isEqualTo(1);
        assertThat(access.rows.get(row.id()).values()).containsEntry("description", revised);
    }

    @Test
    void zonedTimestampPairIsPreservedForUnchangedCellsAndExplicitClearIsAtomic() {
        var row =
                access.put(
                        "main",
                        null,
                        Map.of(
                                "code",
                                "time",
                                "meetingAt",
                                Instant.parse("2026-06-08T01:30:00Z"),
                                "meetingAtTimeZone",
                                "Asia/Shanghai"));
        byte[] file =
                service.export(
                        access,
                        List.of(row.id()),
                        new RecordExchangeService.Export(DATA, "en", "Europe/Berlin"));
        var unchanged = run(file, ImportDuplicateStrategy.ERROR);
        assertThat(unchanged.errorCount()).isZero();
        assertThat(unchanged.updated()).isZero();
        byte[] changed =
                edit(
                        service.export(
                                access,
                                List.of(row.id()),
                                new RecordExchangeService.Export(DATA, "en", "UTC")),
                        "main",
                        "meetingAt",
                        "2026-06-08 10:30:00");
        var result = run(changed, ImportDuplicateStrategy.ERROR);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(access.rows.get(row.id()).values())
                .containsEntry("meetingAt", Instant.parse("2026-06-08T02:30:00Z"));
        byte[] fresh =
                service.export(
                        access,
                        List.of(row.id()),
                        new RecordExchangeService.Export(DATA, "en", "UTC"));
        byte[] clear = edit(edit(fresh, "main", "meetingAt", ""), "main", CLEAR, "meetingAt");
        assertThat(run(clear, ImportDuplicateStrategy.ERROR).updated()).isEqualTo(1);
        assertThat(access.rows.get(row.id()).values())
                .containsEntry("meetingAt", null)
                .containsEntry("meetingAtTimeZone", null);
    }

    @Test
    void duplicateNewChildrenInOneAggregateRequireUniqueMatchingValues() {
        byte[] file =
                file(
                        List.of("code"),
                        List.of(List.of("R", "parent")),
                        List.of(List.of("R", "same", "1"), List.of("R", "same", "2")));
        var result = run(file, ImportDuplicateStrategy.ERROR);
        assertThat(result.created()).isZero();
        assertThat(result.errorCount()).isEqualTo(3);
        assertThat(access.rows).isEmpty();
    }

    @Test
    void explicitMatchingLanguageDoesNotReinterpretUnchangedDictionaryCells() {
        var row = access.put("main", null, Map.of("code", "localized", "status", "open", "qty", 1));
        var exported =
                service.export(
                        access,
                        List.of(row.id()),
                        new RecordExchangeService.Export(DATA, "en", "UTC"));
        var changed = edit(exported, "main", "qty", "2");
        var options = command(null, null, ImportDuplicateStrategy.ERROR, List.of());
        options =
                new RecordExchangeService.Command(
                        null, null, options.sheets(), List.of(), null, "de");
        var preview = service.preview(access, changed, options);
        var result =
                service.execute(
                        access,
                        changed,
                        new RecordExchangeService.Command(
                                preview.documentId(),
                                preview.previewDigest(),
                                options.sheets(),
                                List.of(),
                                null,
                                "de"));
        assertThat(result.errorCount()).isZero();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(access.rows.get(row.id()).values())
                .containsEntry("status", "open")
                .containsEntry("qty", 2);
    }

    private RecordExchangeService.Result run(byte[] bytes, ImportDuplicateStrategy strategy) {
        var preview = service.preview(access, bytes, command(null, null, strategy, List.of()));
        return service.execute(
                access,
                bytes,
                command(preview.documentId(), preview.previewDigest(), strategy, List.of()));
    }

    private RecordExchangeService.Command command(
            String document,
            String digest,
            ImportDuplicateStrategy strategy,
            List<RecordExchangeService.Confirmation> choices) {
        return new RecordExchangeService.Command(
                document,
                digest,
                List.of(
                        new RecordExchangeService.SheetCommand("main", "code", strategy),
                        new RecordExchangeService.SheetCommand("line", "code", strategy)),
                choices,
                null);
    }

    private byte[] file(List<String> names, List<List<Object>> rows, List<List<Object>> children) {
        var columns = new ArrayList<ExcelColumnPlan>();
        columns.add(new ExcelColumnPlan(RELATE, "Relation"));
        names.forEach(name -> columns.add(new ExcelColumnPlan(name, name)));
        var sheets = new ArrayList<ExcelSheetPlan>();
        sheets.add(new ExcelSheetPlan("main", "main", true, columns, rows));
        if (!children.isEmpty())
            sheets.add(
                    new ExcelSheetPlan(
                            "line",
                            "line",
                            false,
                            List.of(
                                    new ExcelColumnPlan(RELATE, "Relation"),
                                    new ExcelColumnPlan("code", "Code"),
                                    new ExcelColumnPlan("qty", "Qty")),
                            children));
        return new ExcelWorkbookPlanWriter().writeToBytes(new ExcelWorkbookPlan(sheets));
    }

    private byte[] edit(byte[] source, String entity, String field, String value) {
        var parsed = new ExcelWorkbookParser(true).parse(source);
        var sheets =
                parsed.sheets().stream()
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
                                                                                column.fieldName(),
                                                                                column.title()))
                                                        .toList(),
                                                sheet.rows().stream()
                                                        .map(
                                                                row ->
                                                                        sheet.columns().stream()
                                                                                .map(
                                                                                        column ->
                                                                                                (Object)
                                                                                                        (sheet.entityAlias()
                                                                                                                                .equals(
                                                                                                                                        entity)
                                                                                                                        && column.fieldName()
                                                                                                                                .equals(
                                                                                                                                        field)
                                                                                                                ? value
                                                                                                                : row
                                                                                                                        .get(
                                                                                                                                column
                                                                                                                                        .columnIndex())))
                                                                                .toList())
                                                        .toList()))
                        .toList();
        return new ExcelWorkbookPlanWriter()
                .writeToBytes(new ExcelWorkbookPlan(parsed.meta(), sheets));
    }

    private byte[] managed(byte[] source) {
        var meta =
                new ExcelWorkbookParser()
                        .parse(
                                service.template(
                                        access,
                                        new RecordExchangeService.Export(DATA, "en", "UTC")))
                        .meta();
        var parsed = new ExcelWorkbookParser(true).parse(source);
        return new ExcelWorkbookPlanWriter()
                .writeToBytes(
                        new ExcelWorkbookPlan(
                                meta,
                                parsed.sheets().stream()
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
                                                                                        row.stream()
                                                                                                .map(
                                                                                                        value ->
                                                                                                                (Object)
                                                                                                                        value)
                                                                                                .toList())
                                                                        .toList()))
                                        .toList()));
    }

    private static final class Fixture implements RecordExchangeAccess {
        final Map<String, Row> rows = new LinkedHashMap<>();
        final Map<String, String> entities = new HashMap<>(),
                parents = new HashMap<>(),
                labels = new HashMap<>();
        final Map<String, List<Candidate>> names = new HashMap<>();
        int saves;
        boolean denyReads;
        Map<String, SourceInput> lastLanguages = Map.of();
        final List<RecordExchangeDefinition.Field> fields =
                List.of(
                        field("code", FieldType.STRING),
                        field("status", FieldType.STRING),
                        field("description", FieldType.TEXT),
                        field("qty", FieldType.INTEGER),
                        field("enabled", FieldType.BOOLEAN),
                        field("remark", FieldType.TEXT),
                        field("serial", FieldType.LONG),
                        field("amount", FieldType.DECIMAL),
                        field("meetingAt", FieldType.ZONED_TIMESTAMP),
                        new RecordExchangeDefinition.Field(
                                "meetingAtTimeZone",
                                "Zone",
                                FieldType.STRING,
                                false,
                                true,
                                false,
                                false,
                                true),
                        new RecordExchangeDefinition.Field(
                                "category",
                                "Category",
                                FieldType.STRING,
                                false,
                                true,
                                true,
                                false,
                                false));

        static RecordExchangeDefinition.Field field(String name, FieldType type) {
            return new RecordExchangeDefinition.Field(
                    name, name, type, false, true, false, false, false);
        }

        public RecordExchangeDefinition definition() {
            return new RecordExchangeDefinition(
                    "test.exchange",
                    List.of(
                            new RecordExchangeDefinition.Entity("main", "Main", null, null, fields),
                            new RecordExchangeDefinition.Entity(
                                    "line", "Line", "lines", "parentId", fields)));
        }

        Row put(String entity, String parent, Map<String, Object> values) {
            var row =
                    new Row(
                            UUID.randomUUID().toString().replace("-", ""),
                            1,
                            Instant.now(),
                            values);
            rows.put(row.id(), row);
            entities.put(row.id(), entity);
            if (parent != null) parents.put(row.id(), parent);
            return row;
        }

        public String inputValue(String entity, String field, String value, String language) {
            if (!"status".equals(field) || "open".equals(value)) return value;
            if (("en".equals(language) && "Open".equals(value))
                    || ("de".equals(language) && "Offen".equals(value))) return "open";
            throw new IllegalArgumentException("Option language mismatch");
        }

        public Object outputValue(String entity, String field, Object value, String language) {
            return "status".equals(field) && "open".equals(value)
                    ? "de".equals(language) ? "Offen" : "Open"
                    : value;
        }

        public Row read(String entity, String parent, String id) {
            if (denyReads)
                throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException(
                        "private-scope-marker");
            var row = rows.get(id);
            if (row == null
                    || !entity.equals(entities.get(id))
                    || !Objects.equals(parent, parents.get(id)))
                throw new IllegalArgumentException("unavailable");
            return row;
        }

        public List<Row> match(String entity, String parent, String field, Object value) {
            return rows.values().stream()
                    .filter(
                            row ->
                                    entity.equals(entities.get(row.id()))
                                            && Objects.equals(parent, parents.get(row.id()))
                                            && Objects.equals(value, row.values().get(field)))
                    .limit(2)
                    .toList();
        }

        public List<Row> children(String entity, String parent, int limit) {
            return rows.values().stream()
                    .filter(
                            row ->
                                    entity.equals(entities.get(row.id()))
                                            && Objects.equals(parent, parents.get(row.id())))
                    .limit(limit)
                    .toList();
        }

        public Row save(
                String entity,
                String parent,
                Row existing,
                Map<String, Object> changes,
                Map<String, SourceInput> languages) {
            saves++;
            lastLanguages = languages;
            if (existing == null) return put(entity, parent, changes);
            var values = new LinkedHashMap<>(existing.values());
            values.putAll(changes);
            var row = new Row(existing.id(), existing.version() + 1, existing.createdAt(), values);
            rows.put(row.id(), row);
            return row;
        }

        public Map<String, Row> saveAggregate(
                AggregateChange root, List<AggregateChange> children) {
            var saved = new LinkedHashMap<String, Row>();
            var parent =
                    Set.of("SKIP", "CONTEXT").contains(root.operation())
                            ? root.existing()
                            : save(
                                    root.entity(),
                                    null,
                                    root.existing(),
                                    root.changes(),
                                    root.languages());
            saved.put(root.key(), parent);
            for (var child : children)
                saved.put(
                        child.key(),
                        Set.of("SKIP", "CONTEXT").contains(child.operation())
                                ? child.existing()
                                : save(
                                        child.entity(),
                                        parent.id(),
                                        child.existing(),
                                        child.changes(),
                                        child.languages()));
            return saved;
        }

        public Reference resolve(
                String entity,
                String parent,
                String field,
                String input,
                String language,
                Map<String, Object> values) {
            return new Reference(names.getOrDefault(input, List.of()));
        }

        public String referenceLabel(
                String entity,
                String parent,
                String field,
                Object ids,
                String language,
                Map<String, Object> values) {
            return ids == null ? null : labels.getOrDefault(ids.toString(), ids.toString());
        }

        public void requireReference(
                String entity,
                String parent,
                String field,
                Object ids,
                Map<String, Object> values) {}

        public BusinessContentService.Sources sources(String entity, String parent, String id) {
            return new BusinessContentService.Sources(read(entity, parent, id).version(), Map.of());
        }

        public BusinessContentTranslationService.Reading reading(
                String entity, String parent, String id, String language) {
            return new BusinessContentTranslationService.Reading(
                    read(entity, parent, id).version(), language, Map.of());
        }

        public BusinessContentTranslationService.Maintenance translation(
                String entity,
                String parent,
                String id,
                String field,
                String language,
                BusinessContentTranslationService.Change change) {
            throw new UnsupportedOperationException();
        }
    }
}
