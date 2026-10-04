package net.ximatai.muyun.spring.platform.exchange.importer;

import net.ximatai.muyun.spring.platform.exchange.exporter.DynamicExportFacade;
import net.ximatai.muyun.spring.platform.exchange.exporter.DynamicExportCommand;
import net.ximatai.muyun.database.core.orm.PageRequest;
import java.util.stream.IntStream;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRuntimeRefresher;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelColumnPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelSheetPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPlan;
import net.ximatai.muyun.spring.platform.exchange.writer.ExcelWorkbookPlanWriter;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = DynamicImportRoundTripIT.TestApplication.class)
class DynamicImportRoundTripIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.default-schema", () -> "public");
    }
    @Autowired DynamicModuleRuntimeRefresher refresher;
    @Autowired DynamicRecordService records;
    @Autowired DynamicImportFacade imports;

    @Test
    void correctedErrorWorkbookRetriesWithoutDuplicatingSuccessfulParentsOrChildren() throws Exception {
        String module = module();
        try (var ignored = TenantContext.use("import-owner")) {
            var first = imports.importWorkbook(command(module, workbook("bad"), ImportDuplicateStrategy.ERROR));
            assertThat(first.executionResult().created()).isEqualTo(2);
            assertThat(first.executionResult().errorRows()).hasSize(1);
            assertThat(first.executionResult().summaries().get("line").errors()).isEqualTo(1);
            byte[] corrected;
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(first.errorWorkbookBytes()));
                 var output = new ByteArrayOutputStream()) {
                assertThat(workbook.getSheet("订单").getLastRowNum()).isEqualTo(2);
                var child = workbook.getSheet("明细");
                assertThat(child.getLastRowNum()).isEqualTo(3);
                child.getRow(3).getCell(2).setCellValue("3");
                workbook.write(output);
                corrected = output.toByteArray();
            }
            var retried = imports.importWorkbook(command(module, corrected, ImportDuplicateStrategy.SKIP));
            assertThat(retried.executionResult().created()).isEqualTo(1);
            assertThat(retried.executionResult().skipped()).isEqualTo(2);
            assertThat(retried.executionResult().errorRows()).isEmpty();
            assertThat(records.count(module, "order", Criteria.of())).isEqualTo(1);
            assertThat(records.count(module, "line", Criteria.of())).isEqualTo(2);
            var repeated = imports.importWorkbook(command(module, corrected, ImportDuplicateStrategy.SKIP));
            assertThat(repeated.executionResult().created()).isZero();
            assertThat(repeated.executionResult().skipped()).isEqualTo(3);
        }
        try (var ignored = TenantContext.use("other-tenant")) {
            assertThat(records.count(module, "order", Criteria.of())).isZero();
            assertThat(records.count(module, "line", Criteria.of())).isZero();
        }
    }

    @Test
    void unpagedExportWritesEveryMatchingRowAndExcludesOtherTenants() throws Exception {
        String module = module();
        try (var ignored = TenantContext.use("export-owner")) {
            List<List<Object>> rows = IntStream.range(0, 25)
                    .mapToObj(index -> List.<Object>of("R-" + index, "EXPORT-" + index)).toList();
            byte[] file = new ExcelWorkbookPlanWriter().writeToBytes(new ExcelWorkbookPlan(List.of(
                    new ExcelSheetPlan("订单", "order", true,
                            List.of(new ExcelColumnPlan("relateId", "关联标识"), new ExcelColumnPlan("orderNo", "订单号")), rows))));
            assertThat(imports.importWorkbook(command(module, file, ImportDuplicateStrategy.ERROR))
                    .executionResult().created()).isEqualTo(25);
            var exports = new DynamicExportFacade(records, null);
            var all = new DynamicExportCommand(
                    records.describe(module), Criteria.of(), new PageRequest(0, Integer.MAX_VALUE), List.of());
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(exports.exportWorkbook(all)))) {
                assertThat(workbook.getSheet("订单").getLastRowNum()).isEqualTo(26);
            }
            var selected = new DynamicExportCommand(
                    records.describe(module), Criteria.of().eq("orderNo", "EXPORT-24"), all.pageRequest(), List.of());
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(exports.exportWorkbook(selected)))) {
                assertThat(workbook.getSheet("订单").getLastRowNum()).isEqualTo(2);
            }
            try (var otherTenant = TenantContext.use("export-other")) {
                try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(exports.exportWorkbook(all)))) {
                    assertThat(workbook.getSheet("订单").getLastRowNum()).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void unhandledWriteFailureRollsBackEarlierWritesInsteadOfReturningPartialSuccess() {
        String module = module();
        try (var ignored = TenantContext.use("rollback-owner")) {
            byte[] file = new ExcelWorkbookPlanWriter().writeToBytes(new ExcelWorkbookPlan(List.of(
                    new ExcelSheetPlan("订单", "order", true,
                            List.of(new ExcelColumnPlan("relateId", "关联标识"), new ExcelColumnPlan("orderNo", "订单号")),
                            List.of(List.of("R-1", "valid"), List.of("R-2", "x".repeat(100)))))));
            assertThatThrownBy(() -> imports.importWorkbook(command(module, file, ImportDuplicateStrategy.ERROR)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(records.count(module, "order", Criteria.of())).isZero();
        }
    }

    private String module() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String alias = "sales.import_" + suffix;
        refresher.refresh(ModuleDefinition.builder(alias, "导入往返")
                .entities(List.of(
                        new EntityDefinition("order", "import_order_" + suffix, "订单",
                                List.of(FieldDefinition.string("orderNo", "订单号").column("order_no").length(32).required()), Set.of(EntityCapability.EXCHANGE)),
                        new EntityDefinition("line", "import_line_" + suffix, "明细",
                                List.of(FieldDefinition.string("orderId", "订单").column("order_id").required(),
                                        FieldDefinition.string("sku", "商品").required(), FieldDefinition.integer("qty", "数量")))))
                .relations(List.of(EntityRelationDefinition.child("lines", "order", "line", "orderId"))).build());
        return alias;
    }
    private DynamicImportCommand command(String module, byte[] bytes, ImportDuplicateStrategy strategy) {
        var parsed = imports.parse(records.describe(module), bytes);
        return new DynamicImportCommand(records.describe(module), bytes,
                new BuildDynamicImportPlanCommand(module, "orderNo", strategy,
                        parsed.sheets().stream().filter(sheet -> !sheet.main())
                                .map(sheet -> new BuildDynamicImportPlanCommand.ChildSheetCommand(sheet.entityAlias(), "sku", strategy)).toList()));
    }
    private byte[] workbook(String badQty) {
        return new ExcelWorkbookPlanWriter().writeToBytes(new ExcelWorkbookPlan(List.of(
                new ExcelSheetPlan("订单", "order", true,
                        List.of(new ExcelColumnPlan("relateId", "关联标识"), new ExcelColumnPlan("orderNo", "订单号")),
                        List.of(List.of("R-1", "SO-1"))),
                new ExcelSheetPlan("明细", "line", false,
                        List.of(new ExcelColumnPlan("relateId", "关联标识"), new ExcelColumnPlan("sku", "商品"), new ExcelColumnPlan("qty", "数量")),
                        List.of(List.of("R-1", "ok", "1"), List.of("R-1", "fix", badQty))))));
    }
    @SpringBootConfiguration @EnableAutoConfiguration @EnableTransactionManagement
    static class TestApplication {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean DynamicRecordRuntime runtime(IDatabaseOperations<?> database) { return DynamicRecordRuntime.builder(database).build(); }
        @Bean DynamicSchemaService schema(IDatabaseOperations<?> database) { return new DynamicSchemaService(database); }
        @Bean DynamicModuleRuntimeRefresher refresher(DynamicSchemaService schema, DynamicRecordRuntime runtime) { return new DynamicModuleRuntimeRefresher(schema, runtime); }
        @Bean DynamicRecordService records(DynamicRecordRuntime runtime) { return new DynamicRecordService(runtime); }
        @Bean DynamicImportFacade imports(DynamicRecordService records) { return new DynamicImportFacade(records); }
    }
}
