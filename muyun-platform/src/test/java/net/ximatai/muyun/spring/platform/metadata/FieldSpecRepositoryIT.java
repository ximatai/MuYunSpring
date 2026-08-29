package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = FieldSpecRepositoryIT.TestApplication.class)
class FieldSpecRepositoryIT extends PlatformPostgresIntegrationTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    private final FieldSpecService fieldTypeService;
    private final MetadataService metadataService;
    private final DataSource dataSource;
    private final MetadataSchemaTransactionProbe transactionProbe;

    @Autowired
    FieldSpecRepositoryIT(FieldSpecService fieldTypeService, MetadataService metadataService, DataSource dataSource,
                          MetadataSchemaTransactionProbe transactionProbe) {
        this.fieldTypeService = fieldTypeService;
        this.metadataService = metadataService;
        this.dataSource = dataSource;
        this.transactionProbe = transactionProbe;
    }

    @Test
    void shouldPersistQueryOperatorsAsJsonSetThroughRepository() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        FieldSpec fieldType = new FieldSpec();
        fieldType.setAlias("string_" + suffix);
        fieldType.setTitle("String " + suffix);
        fieldType.setFieldType(FieldType.STRING);
        fieldType.setDefaultLength(128);
        fieldType.setDefaultQueryOperator(DynamicQueryOperator.LIKE);
        fieldType.setQueryOperators(Set.of(" LIKE ", "EQ"));

        String id = fieldTypeService.insert(fieldType);

        FieldSpec selected = fieldTypeService.select(id);
        assertThat(selected.getQueryOperators()).containsExactly("EQ", "LIKE");
        assertThat(selected.queryDefinition().operators()).containsExactlyInAnyOrder(DynamicQueryOperator.EQ, DynamicQueryOperator.LIKE);
    }

    @Test
    void shouldQueryJsonSetFieldWithCollectionCriteria() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        FieldSpec stringType = fieldType("string_" + suffix, FieldType.STRING,
                Set.of("LIKE", "EQ"), Set.of("input", "select"));
        FieldSpec dateType = fieldType("date_" + suffix, FieldType.DATE,
                Set.of("BETWEEN", "EQ"), Set.of("date"));
        FieldSpec emptyType = fieldType("empty_" + suffix, FieldType.TEXT,
                Set.of(), Set.of());
        emptyType.setDefaultQueryOperator(null);
        fieldTypeService.insert(stringType);
        fieldTypeService.insert(dateType);
        fieldTypeService.insert(emptyType);
        List<String> aliases = List.of(stringType.getAlias(), dateType.getAlias(), emptyType.getAlias());

        assertThat(fieldTypeService.list(Criteria.of()
                        .in("alias", aliases)
                        .contains("queryOperators", "LIKE")))
                .extracting(FieldSpec::getAlias)
                .containsExactly(stringType.getAlias());
        assertThat(fieldTypeService.list(Criteria.of()
                        .in("alias", aliases)
                        .containsAny("queryOperators", List.of("LIKE", "BETWEEN"))))
                .extracting(FieldSpec::getAlias)
                .containsExactlyInAnyOrder(stringType.getAlias(), dateType.getAlias());
        assertThat(fieldTypeService.list(Criteria.of()
                        .in("alias", aliases)
                        .containsAll("uiControlAliases", List.of("input", "select"))))
                .extracting(FieldSpec::getAlias)
                .containsExactly(stringType.getAlias());
        assertThat(fieldTypeService.list(Criteria.of()
                        .in("alias", aliases)
                        .isEmpty("uiControlAliases")))
                .extracting(FieldSpec::getAlias)
                .containsExactly(emptyType.getAlias());
        assertThat(fieldTypeService.list(Criteria.of()
                        .in("alias", aliases)
                        .isNotEmpty("uiControlAliases")))
                .extracting(FieldSpec::getAlias)
                .containsExactlyInAnyOrder(stringType.getAlias(), dateType.getAlias());
    }

    @Test
    void shouldRollbackMetadataDaoAndDynamicDdlInOneSpringTransaction() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String alias = "tx_" + suffix;
        String tableName = "app_metadata_tx_" + suffix;
        assertThatThrownBy(() -> transactionProbe.insertMetadataEnsureTableThenFail(metadataService,
                new EntityDefinition(alias, tableName, "Tx", List.of(FieldDefinition.string("code", "Code")))))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("rollback metadata schema transaction");

        assertThat(metadataService.count(Criteria.of().eq("alias", alias))).isZero();
        try (Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement("""
                     select count(*) from information_schema.tables
                     where table_schema = 'public' and table_name = ?
                     """)) {
            statement.setString(1, tableName);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                result.next();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    private FieldSpec fieldType(String alias,
                                        FieldType fieldType,
                                        Set<String> queryOperators,
                                        Set<String> uiControlAliases) {
        FieldSpec type = new FieldSpec();
        type.setAlias(alias);
        type.setTitle(alias);
        type.setFieldType(fieldType);
        type.setDefaultQueryOperator(DynamicQueryOperator.defaultOperator(fieldType));
        type.setQueryOperators(queryOperators);
        type.setUiControlAliases(uiControlAliases);
        return type;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @EnableMuYunRepositories(basePackageClasses = {FieldSpecDao.class, MetadataDao.class})
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
        FieldSpecService fieldTypeService(FieldSpecDao fieldTypeDao) {
            return new FieldSpecService(fieldTypeDao);
        }

        @Bean
        MetadataService metadataService(MetadataDao metadataDao) {
            return new MetadataService(metadataDao);
        }

        @Bean
        DynamicSchemaService dynamicSchemaService(net.ximatai.muyun.database.core.IDatabaseOperations<?> operations) {
            return new DynamicSchemaService(operations);
        }

        @Bean
        MetadataSchemaTransactionProbe metadataSchemaTransactionProbe(DynamicSchemaService schemaService) {
            return new MetadataSchemaTransactionProbe(schemaService);
        }
    }

    static class MetadataSchemaTransactionProbe {
        private final DynamicSchemaService schemaService;

        MetadataSchemaTransactionProbe(DynamicSchemaService schemaService) {
            this.schemaService = schemaService;
        }

        @Transactional
        public void insertMetadataEnsureTableThenFail(MetadataService metadataService, EntityDefinition entity) {
            Metadata metadata = new Metadata();
            metadata.setApplicationAlias("crm");
            metadata.setAlias(entity.alias());
            metadata.setTitle(entity.name());
            metadata.setSchemaName(entity.schemaName());
            metadata.setTableName(entity.tableName());
            metadataService.insert(metadata);
            schemaService.ensureTable(entity);
            throw new RuntimeException("rollback metadata schema transaction");
        }
    }
}
