package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.MigrationOptions;
import net.ximatai.muyun.database.core.orm.MigrationResult;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.PlatformSchemaMigrationPolicy;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.schema.DynamicTableMapper;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplateService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PlatformMetadataSchemaEnsureServiceTest {

    @Test
    void shouldEnsureMetadataSchemaAfterMetadataSave() {
        PlatformMetadataSchemaEnsureService schemaEnsureService = mock(PlatformMetadataSchemaEnsureService.class);
        MetadataService metadataService = new MetadataService(new TestMemoryDao<>(), Optional.of(schemaEnsureService));
        Metadata metadata = metadata("crm", "customer");

        String metadataId = metadataService.insert(metadata);

        verify(schemaEnsureService).ensure(metadataId);
    }

    @Test
    void shouldEnsureOwningMetadataSchemaAfterMetadataFieldSave() {
        PlatformMetadataSchemaEnsureService schemaEnsureService = mock(PlatformMetadataSchemaEnsureService.class);
        MetadataService metadataService = new MetadataService(new TestMemoryDao<>());
        FieldSpecService fieldTypeService = new FieldSpecService(new TestMemoryDao<>());
        fieldTypeService.insert(fieldType("string", FieldType.STRING, 128));
        MetadataFieldService fieldService = new MetadataFieldService(
                new TestMemoryDao<>(), metadataService, fieldTypeService, Optional.empty(),
                Optional.of(schemaEnsureService));
        String metadataId = metadataService.insert(metadata("crm", "customer"));

        fieldService.insert(field(metadataId, "customerName", "customer_name", "string"));

        verify(schemaEnsureService).ensure(metadataId);
    }

    @Test
    void shouldCompileSingleMetadataIntoEntityDefinitionForSchemaEnsure() {
        TestContext context = testContext();
        String metadataId = context.metadataService.insert(metadata("crm", "customer"));
        Metadata metadata = context.metadataService.select(metadataId);
        metadata.setDataScopeEnabled(true);
        context.metadataService.update(metadata);
        context.fieldService.insert(titleField(metadataId));
        context.fieldService.insert(field(metadataId, "customerName", "customer_name", "string"));
        context.fieldService.insert(field(metadataId, PlatformAbilityFields.TREE_PARENT_FIELD,
                PlatformAbilityFields.TREE_PARENT_COLUMN, "string"));
        context.fieldService.insert(sortField(metadataId));
        context.fieldService.insert(field(metadataId, PlatformAbilityFields.ENABLED_FIELD,
                PlatformAbilityFields.ENABLED_COLUMN, "boolean"));

        EntityDefinition entity = context.compiler.compile(metadataId);

        assertThat(entity.alias()).isEqualTo("customer");
        assertThat(entity.schemaName()).isEqualTo(MetadataService.DEFAULT_SCHEMA);
        assertThat(entity.tableName()).isEqualTo("crm_customer");
        assertThat(entity.fields()).extracting(FieldDefinition::fieldName)
                .containsExactly("title", "customerName", "parentId", "sortOrder", "enabled");
        assertThat(entity.capabilities()).contains(
                EntityCapability.CRUD,
                EntityCapability.TREE,
                EntityCapability.SORT,
                EntityCapability.REFERENCE,
                EntityCapability.ENABLE,
                EntityCapability.DATA_SCOPE
        );
    }

    @Test
    void shouldKeepVirtualMetadataFieldOutOfSchemaTableMapping() {
        TestContext context = testContext();
        String metadataId = context.metadataService.insert(metadata("crm", "customer"));
        context.fieldService.insert(field(metadataId, "customerName", "customer_name", "string"));
        MetadataField virtualField = field(metadataId, "displayName", "display_name", "string");
        virtualField.setFieldForm(MetadataFieldForm.VIRTUAL);
        context.fieldService.insert(virtualField);

        EntityDefinition entity = context.compiler.compile(metadataId);

        assertThat(entity.fields())
                .extracting(FieldDefinition::fieldName)
                .containsExactly("customerName", "displayName");
        assertThat(entity.fields())
                .filteredOn(field -> field.fieldName().equals("displayName"))
                .singleElement()
                .satisfies(field -> assertThat(field.isPhysical()).isFalse());
        assertThat(new DynamicTableMapper().toTable(entity).getColumns())
                .extracting(net.ximatai.muyun.database.core.builder.Column::getName)
                .contains("customer_name")
                .doesNotContain("display_name");
    }

    @Test
    void shouldCallDynamicSchemaServiceEnsureTableForSingleMetadata() {
        TestContext context = testContext();
        RecordingSchemaService schemaService = new RecordingSchemaService();
        PlatformMetadataSchemaEnsureService ensureService =
                new PlatformMetadataSchemaEnsureService(context.compiler, schemaService);
        String metadataId = context.metadataService.insert(metadata("crm", "customer"));
        context.fieldService.insert(titleField(metadataId));

        boolean changed = ensureService.ensureNow(metadataId);

        assertThat(changed).isTrue();
        assertThat(schemaService.ensuredEntities).hasSize(1);
        assertThat(schemaService.ensuredEntities.getFirst().alias()).isEqualTo("customer");
        assertThat(schemaService.ensuredEntities.getFirst().fields())
                .extracting(FieldDefinition::fieldName)
                .containsExactly("title");
    }

    @Test
    void shouldEnsureSchemaBeforeCommitSoFailureCanAbortPublication() {
        clearTransactionState();
        try {
            TestContext context = testContext();
            RecordingSchemaService schemaService = new RecordingSchemaService();
            PlatformMetadataSchemaEnsureService ensureService =
                    new PlatformMetadataSchemaEnsureService(context.compiler, schemaService);
            String metadataId = context.metadataService.insert(metadata("crm", "customer"));
            context.fieldService.insert(titleField(metadataId));
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);

            ensureService.ensure(metadataId);

            ensureService.ensure(metadataId);
            assertThat(schemaService.ensuredEntities).isEmpty();
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.beforeCommit(false));
            assertThat(schemaService.ensuredEntities).hasSize(1);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(schemaService.ensuredEntities).hasSize(1);
        } finally {
            clearTransactionState();
        }
    }

    @Test
    void shouldPropagateSchemaEnsureFailureWithoutTransaction() {
        TestContext context = testContext();
        PlatformMetadataSchemaEnsureService ensureService =
                new PlatformMetadataSchemaEnsureService(context.compiler, new FailingSchemaService());
        String metadataId = context.metadataService.insert(metadata("crm", "customer"));

        assertThatThrownBy(() -> ensureService.ensure(metadataId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema failed");
    }

    @Test
    void uiAndQuerySaveServicesShouldNotDependOnMetadataSchemaEnsure() {
        assertThat(hasSchemaEnsureDependency(PlatformUiConfigService.class)).isFalse();
        assertThat(hasSchemaEnsureDependency(PlatformQueryTemplateService.class)).isFalse();
    }

    private TestContext testContext() {
        MetadataService metadataService = new MetadataService(new TestMemoryDao<>());
        FieldSpecService fieldTypeService = new FieldSpecService(new TestMemoryDao<>());
        fieldTypeService.insert(fieldType("string", FieldType.STRING, 128));
        fieldTypeService.insert(fieldType("boolean", FieldType.BOOLEAN, null));
        fieldTypeService.insert(fieldType("integer", FieldType.INTEGER, null));
        MetadataFieldService fieldService = new MetadataFieldService(
                new TestMemoryDao<>(), metadataService, fieldTypeService);
        MetadataFieldConfigService configService = mock(MetadataFieldConfigService.class);
        MetadataFieldDefinitionCompiler fieldCompiler =
                new MetadataFieldDefinitionCompiler(fieldTypeService, configService, null, fieldService);
        PlatformMetadataEntityDefinitionCompiler compiler =
                new PlatformMetadataEntityDefinitionCompiler(metadataService, fieldService, fieldCompiler);
        return new TestContext(metadataService, fieldService, compiler);
    }

    private Metadata metadata(String applicationAlias, String alias) {
        Metadata metadata = new Metadata();
        metadata.setApplicationAlias(applicationAlias);
        metadata.setAlias(alias);
        metadata.setTitle(alias);
        return metadata;
    }

    private MetadataField titleField(String metadataId) {
        MetadataField field = field(metadataId, "title", "title", "string");
        field.setTitleField(true);
        return field;
    }

    private MetadataField sortField(String metadataId) {
        MetadataField field = field(metadataId, "sortOrder", "sort_order", "integer");
        field.setSortableField(true);
        return field;
    }

    private MetadataField field(String metadataId, String fieldName, String columnName, String fieldSpecAlias) {
        MetadataField field = new MetadataField();
        field.setMetadataId(metadataId);
        field.setFieldName(fieldName);
        field.setColumnName(columnName);
        field.setTitle(fieldName);
        field.setFieldSpecAlias(fieldSpecAlias);
        return field;
    }

    private FieldSpec fieldType(String alias, FieldType type, Integer length) {
        FieldSpec fieldType = new FieldSpec();
        fieldType.setAlias(alias);
        fieldType.setTitle(alias);
        fieldType.setFieldType(type);
        fieldType.setDefaultLength(length);
        return fieldType;
    }

    private void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private boolean hasSchemaEnsureDependency(Class<?> serviceClass) {
        for (Constructor<?> constructor : serviceClass.getDeclaredConstructors()) {
            for (Type parameterType : constructor.getGenericParameterTypes()) {
                if (isSchemaEnsureType(parameterType)) {
                    return true;
                }
            }
        }
        for (Field field : serviceClass.getDeclaredFields()) {
            if (isSchemaEnsureType(field.getGenericType())) {
                return true;
            }
        }
        return false;
    }

    private boolean isSchemaEnsureType(Type type) {
        if (type == PlatformMetadataSchemaEnsureService.class) {
            return true;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                if (isSchemaEnsureType(argument)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record TestContext(MetadataService metadataService,
                               MetadataFieldService fieldService,
                               PlatformMetadataEntityDefinitionCompiler compiler) {
    }

    private static class RecordingSchemaService extends DynamicSchemaService {
        private final List<EntityDefinition> ensuredEntities = new ArrayList<>();

        RecordingSchemaService() {
            super(null);
        }

        @Override
        public MigrationResult ensureTable(EntityDefinition entity, MigrationOptions options) {
            MigrationOptions effectiveOptions = PlatformSchemaMigrationPolicy.executeByDefault().resolve(options);
            ensuredEntities.add(entity);
            return new MigrationResult(true, effectiveOptions.isDryRun(), false, List.of(), List.of());
        }
    }

    private static class FailingSchemaService extends DynamicSchemaService {
        FailingSchemaService() {
            super(null);
        }

        @Override
        public MigrationResult ensureTable(EntityDefinition entity, MigrationOptions options) {
            throw new IllegalStateException("schema failed");
        }
    }
}
