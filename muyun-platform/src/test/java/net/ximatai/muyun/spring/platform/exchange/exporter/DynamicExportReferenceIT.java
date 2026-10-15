package net.ximatai.muyun.spring.platform.exchange.exporter;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.common.i18n.LanguageContext;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRuntimeRefresher;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPresentation;
import net.ximatai.muyun.spring.platform.reference.PlatformReferenceTargetResolver;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import net.ximatai.muyun.spring.platform.support.TestBeanProviders;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DynamicExportReferenceIT.Application.class)
class DynamicExportReferenceIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
        registry.add("muyun.database.default-schema", () -> "public");
    }

    @Autowired IDatabaseOperations<?> database;
    @Autowired TargetService specs;

    @AfterEach
    void resetTargets() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void exportsStaticAndDynamicOriginalTitlesThroughRealRepositoriesWithoutCrossingTenants() throws Exception {
        var runtime = DynamicRecordRuntime.builder(database).build();
        var records = new DynamicRecordService(runtime);
        PlatformAbilityRuntime.configureReferenceTargetResolver(new PlatformReferenceTargetResolver(
                new StaticAbilityCatalog(List.of(specs)), runtime, records));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String module = "sales.export_" + suffix;
        var order = new EntityDefinition("order", "export_order_" + suffix, "Orders", List.of(
                FieldDefinition.string("spec", "Specification"), FieldDefinition.string("customer", "Customer")));
        var customer = new EntityDefinition("customer", "export_customer_" + suffix, "Customers",
                List.of(FieldDefinition.titleField()), Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE));
        new DynamicModuleRuntimeRefresher(new DynamicSchemaService(database), runtime).refresh(
                ModuleDefinition.builder(module, "Export references").entities(List.of(order, customer))
                        .references(List.of(EntityReferenceDefinition.to("order", "spec", "fixture.spec"),
                                EntityReferenceDefinition.to("order", "customer", ReferenceTarget.of(module, "customer"))))
                        .build());
        String foreignCustomer;
        try (var ignored = TenantContext.use("export-other")) {
            var foreign = records.newRecord(module, "customer").setValue("title", "Other tenant");
            foreignCustomer = records.create(module, "customer", foreign);
        }
        var exports = new DynamicExportFacade(records, null,
                new ExcelWorkbookPresentation(TestBeanProviders.empty(net.ximatai.muyun.spring.common.i18n.TextResolver.class)));
        try (var ignored = TenantContext.use("export-owner"); var language = LanguageContext.use("en")) {
            var spec = new Target(); spec.setTitle("规格原文 {value}");
            String specId = specs.insert(spec);
            var ownCustomer = records.newRecord(module, "customer").setValue("title", "客户原文 {value}");
            String customerId = records.create(module, "customer", ownCustomer);
            var record = records.newRecord(module, "order")
                    .setValue("spec", specId).setValue("customer", customerId);
            assertThat(specs.referenceFacts(List.of(specId), List.of())).as("static target facts").containsKey(specId);
            assertThat(records.referenceAbility(ReferenceTarget.of(module, "customer")).orElseThrow()
                    .referenceFacts(List.of(customerId), List.of("tenantId"))).as("dynamic target facts").containsKey(customerId);
            String recordId = records.create(module, "order", record);
            var command = new DynamicExportCommand(records.describe(module), Criteria.of(), new PageRequest(0, Integer.MAX_VALUE), List.of());
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(exports.exportWorkbook(command)))) {
                var sheet = workbook.getSheet("Orders");
                assertThat(sheet.getLastRowNum()).isEqualTo(2);
                assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("order.spec");
                assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("规格原文 {value}");
                assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("客户原文 {value}");
            }
            assertThat(records.referenceAbility(ReferenceTarget.of(module, "customer")).orElseThrow()
                    .titles(List.of(customerId, foreignCustomer))).containsOnlyKeys(customerId);
            assertThat(records.select(module, "order", recordId).getValue("spec")).isEqualTo(specId);
            specs.delete(specId);
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(exports.exportWorkbook(command)))) {
                assertThat(workbook.getSheet("Orders").getRow(2).getCell(1).getStringCellValue()).isEqualTo(specId);
            }
            try (var other = TenantContext.use("export-other");
                 var workbook = new XSSFWorkbook(new ByteArrayInputStream(exports.exportWorkbook(command)))) {
                assertThat(workbook.getSheet("Orders").getLastRowNum()).isEqualTo(1);
            }
        }
    }

    @Table(name = "export_reference_target")
    public static class Target extends StandardTitledEntity {}

    @MuYunRepository
    public interface TargetDao extends BaseDao<Target, String> {}

    static class TargetService extends AbstractAbilityService<Target> implements ReferenceAbility<Target>, SoftDeleteAbility<Target> {
        TargetService(TargetDao dao) { super("fixture.spec", Target.class, dao); }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = TargetDao.class)
    static class Application {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean TargetService specs(TargetDao dao) { return new TargetService(dao); }
    }
}
