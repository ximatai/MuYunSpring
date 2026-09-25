package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.platform.reference.PlatformReferenceLoadResolver;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.platform.support.TestBeanProviders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
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
    private final FieldUiControlService fieldUiControlService;
    private final FieldUiControlPropertyService fieldUiControlPropertyService;
    private final FieldUiControlBindingService fieldUiControlBindingService;
    private final MetadataService metadataService;
    private final DataSource dataSource;
    private final MetadataSchemaTransactionProbe transactionProbe;

    @Autowired
    FieldSpecRepositoryIT(FieldSpecService fieldTypeService, FieldUiControlService fieldUiControlService,
                          FieldUiControlPropertyService fieldUiControlPropertyService,
                          FieldUiControlBindingService fieldUiControlBindingService,
                          MetadataService metadataService, DataSource dataSource,
                          MetadataSchemaTransactionProbe transactionProbe) {
        this.fieldTypeService = fieldTypeService;
        this.fieldUiControlService = fieldUiControlService;
        this.fieldUiControlPropertyService = fieldUiControlPropertyService;
        this.fieldUiControlBindingService = fieldUiControlBindingService;
        this.metadataService = metadataService;
        this.dataSource = dataSource;
        this.transactionProbe = transactionProbe;
    }

    @BeforeEach
    void configureReferenceLoads() {
        PlatformAbilityRuntime.configureReferenceLoadResolver(new PlatformReferenceLoadResolver(
                new StaticAbilityCatalog(List.of(fieldTypeService, fieldUiControlService,
                        fieldUiControlPropertyService, fieldUiControlBindingService))));
        PlatformAbilityRuntime.configureChildAbilityResolver(request -> {
            if (FieldUiControlProperty.class.equals(request.staticModel())) {
                return Optional.of(fieldUiControlPropertyService);
            }
            if (FieldUiControlBinding.class.equals(request.staticModel())) {
                return Optional.of(fieldUiControlBindingService);
            }
            return Optional.empty();
        });
    }

    @AfterEach
    void resetReferenceLoads() {
        PlatformAbilityRuntime.resetReferenceLoadResolver();
        PlatformAbilityRuntime.resetChildAbilityResolver();
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
        for (String kind : List.of("input", "select", "date")) {
            FieldUiControl control = new FieldUiControl();
            control.setAlias(kind + "_" + suffix);
            control.setTitle(kind);
            fieldUiControlService.insert(control);
        }
        FieldSpec stringType = fieldType("string_" + suffix, FieldType.STRING,
                Set.of("LIKE", "EQ"), Set.of("input_" + suffix, "select_" + suffix));
        FieldSpec dateType = fieldType("date_" + suffix, FieldType.DATE,
                Set.of("BETWEEN", "EQ"), Set.of("date_" + suffix));
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
                        .containsAll("uiControlAliases", List.of("input_" + suffix, "select_" + suffix))))
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
    void shouldReloadPersistedFieldUiControlReferenceTitlesWithoutPersistingReadProjections() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        FieldSpec fieldSpec = fieldType("string_" + suffix, FieldType.STRING, Set.of(), Set.of());
        fieldSpec.setTitle("文本字段 " + suffix);
        String fieldSpecId = fieldTypeService.insert(fieldSpec);

        FieldUiControl control = new FieldUiControl();
        control.setAlias("control_" + suffix);
        control.setTitle("组合控件 " + suffix);
        control.setDefaultFieldSpecAlias(fieldSpecId);
        control.setValueShape(FieldUiControlValueShape.COMPOSITE);
        control.setPrimaryValueKey("value");
        String controlId = fieldUiControlService.insert(control);

        FieldUiControlProperty property = new FieldUiControlProperty();
        property.setFieldUiControlAlias(controlId);
        property.setAttributeAlias("placeholder");
        property.setTitle("占位提示");
        property.setValueFieldSpecAlias(fieldSpecId);
        String propertyId = fieldUiControlPropertyService.insert(property);

        FieldUiControlBinding binding = new FieldUiControlBinding();
        binding.setFieldUiControlAlias(controlId);
        binding.setValueKey("value");
        binding.setTitle("主值");
        binding.setValueFieldSpecAlias(fieldSpecId);
        String bindingId = fieldUiControlBindingService.insert(binding);

        assertThat(fieldUiControlService.selectActiveRaw(controlId).getDefaultFieldSpecTitle()).isNull();
        assertThat(fieldUiControlPropertyService.selectActiveRaw(propertyId).getValueFieldSpecTitle()).isNull();
        assertThat(fieldUiControlBindingService.selectActiveRaw(bindingId).getValueFieldSpecTitle()).isNull();

        FieldUiControl selectedControl = fieldUiControlService.select(controlId);
        FieldUiControlProperty selectedProperty = fieldUiControlPropertyService.select(propertyId);
        FieldUiControlBinding selectedBinding = fieldUiControlBindingService.select(bindingId);

        assertThat(selectedControl.getId()).isEqualTo(controlId);
        assertThat(selectedControl.getDefaultFieldSpecAlias()).isEqualTo(fieldSpecId);
        assertThat(selectedControl.getDefaultFieldSpecTitle()).isEqualTo(fieldSpec.getTitle());
        assertThat(selectedProperty.getValueFieldSpecAlias()).isEqualTo(fieldSpecId);
        assertThat(selectedProperty.getValueFieldSpecTitle()).isEqualTo(fieldSpec.getTitle());
        assertThat(selectedBinding.getValueFieldSpecAlias()).isEqualTo(fieldSpecId);
        assertThat(selectedBinding.getValueFieldSpecTitle()).isEqualTo(fieldSpec.getTitle());

        selectedControl.setTitle("已更新的组合控件 " + suffix);
        fieldUiControlService.update(selectedControl);

        FieldUiControl reloadedControl = fieldUiControlService.select(controlId);
        assertThat(reloadedControl.getId()).isEqualTo(controlId);
        assertThat(reloadedControl.getDefaultFieldSpecAlias()).isEqualTo(fieldSpecId);
        assertThat(reloadedControl.getDefaultFieldSpecTitle()).isEqualTo(fieldSpec.getTitle());
        assertThat(fieldUiControlService.selectActiveRaw(controlId).getDefaultFieldSpecTitle()).isNull();
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
    @EnableMuYunRepositories(basePackageClasses = {FieldSpecDao.class, FieldUiControlDao.class,
            FieldUiControlPropertyDao.class, FieldUiControlBindingDao.class, MetadataDao.class})
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
        FieldSpecService fieldTypeService(FieldSpecDao fieldTypeDao, FieldUiControlDao fieldUiControlDao) {
            return new FieldSpecService(fieldTypeDao, fieldUiControlDao);
        }

        @Bean
        FieldUiControlService fieldUiControlService(FieldUiControlDao fieldUiControlDao,
                                                    FieldSpecService fieldTypeService) {
            return new FieldUiControlService(fieldUiControlDao, fieldTypeService, Mockito.mock(BaseDao.class));
        }

        @Bean
        FieldUiControlPropertyService fieldUiControlPropertyService(FieldUiControlPropertyDao fieldUiControlPropertyDao,
                                                                      FieldUiControlService fieldUiControlService,
                                                                      FieldSpecService fieldTypeService) {
            return new FieldUiControlPropertyService(fieldUiControlPropertyDao, fieldUiControlService, fieldTypeService);
        }

        @Bean
        FieldUiControlBindingService fieldUiControlBindingService(FieldUiControlBindingDao fieldUiControlBindingDao,
                                                                   FieldUiControlService fieldUiControlService,
                                                                   FieldSpecService fieldTypeService) {
            return new FieldUiControlBindingService(fieldUiControlBindingDao, fieldUiControlService, fieldTypeService);
        }

        @Bean
        MetadataService metadataService(MetadataDao metadataDao) {
            return new MetadataService(
                    metadataDao,
                    TestBeanProviders.empty(PlatformMetadataSchemaEnsureService.class),
                    Optional.empty(),
                    TestBeanProviders.empty(ConfigurationReferenceDeletionGuard.class),
                    TestBeanProviders.empty(ModuleMetadataRelationService.class),
                    event -> {});
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
