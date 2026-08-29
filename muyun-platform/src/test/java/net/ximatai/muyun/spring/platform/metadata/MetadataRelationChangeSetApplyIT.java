package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleDao;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
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
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies that the real publisher, metadata DAO and PostgreSQL DDL share one transaction. */
@SpringBootTest(classes = MetadataRelationChangeSetApplyIT.TestApplication.class)
class MetadataRelationChangeSetApplyIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Autowired private MetadataRelationChangeSetApplyService applyService;
    @Autowired private MetadataRelationChangeSetPreviewService previewService;
    @Autowired private MetadataService metadataService;
    @Autowired private MetadataFieldService fieldService;
    @Autowired private ModuleMetadataRelationService relationService;
    @Autowired private FieldSpecService fieldSpecService;
    @Autowired private PlatformModuleService moduleService;
    @Autowired private TestSchemaEnsureService schemaEnsureService;
    @Autowired private PlatformMetadataEntityDefinitionCompiler entityCompiler;
    @Autowired private PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator;
    @Autowired private DataSource dataSource;

    private String moduleAlias;
    private String relationId;
    private Metadata metadata;
    private String stringSpecAlias;

    @BeforeEach
    void setUp() {
        reset(moduleService, refreshCoordinator);
        schemaEnsureService.failAfterEnsure = false;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        moduleAlias = "crm.change_" + suffix;
        PlatformModule module = new PlatformModule();
        module.setAlias(moduleAlias);
        module.setModuleKind(ModuleKind.DYNAMIC);
        when(moduleService.select(moduleAlias)).thenReturn(module);

        FieldSpec string = new FieldSpec();
        stringSpecAlias = "string_" + suffix;
        string.setAlias(stringSpecAlias);
        string.setTitle("String");
        string.setFieldType(FieldType.STRING);
        string.setDefaultLength(128);
        fieldSpecService.insert(string);
        if (fieldSpecService.list(Criteria.of().eq("alias", "boolean")).isEmpty()) {
            FieldSpec bool = new FieldSpec();
            bool.setAlias("boolean");
            bool.setTitle("Boolean");
            bool.setFieldType(FieldType.BOOLEAN);
            fieldSpecService.insert(bool);
        }

        metadata = new Metadata();
        metadata.setApplicationAlias("crm");
        metadata.setAlias("change_" + suffix);
        metadata.setTitle("Change " + suffix);
        metadata.setSchemaName("public");
        metadata.setTableName("app_change_" + suffix);
        metadataService.insert(metadata);

        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(moduleAlias);
        relation.setMetadataId(metadata.getId());
        relation.setRelationRole(RelationRole.MAIN);
        relation.setRelationAlias(metadata.getAlias());
        relation.setTitle(metadata.getTitle());
        relationId = relationService.insert(relation);
    }

    @Test
    void shouldCommitMetadataFieldsAndDdlThenActivate() throws Exception {
        MetadataRelationChangeSetPreviewCommand proposal = proposal("title", "title", fieldSpecAlias(), true);
        MetadataRelationChangeSetPreview preview = previewService.preview(moduleAlias, relationId, proposal);
        assertThat(preview.errors()).isEmpty();

        applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(proposal, preview.proposalFingerprint()));

        assertThat(metadataService.select(metadata.getId()).getCapabilityDeclarations()).contains("ENABLE");
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId())))
                .extracting(MetadataField::getFieldName).contains("title", "enabled");
        assertThat(columnExists(metadata.getTableName(), "title")).isTrue();
        assertThat(columnExists(metadata.getTableName(), "enabled")).isTrue();
        verify(refreshCoordinator).activateByMetadataIdNow(metadata.getId());
    }

    @Test
    void shouldMaterializeTreeWithCanonicalParentFieldShapeBeforeActivation() {
        MetadataRelationChangeSetPreviewCommand proposal = new MetadataRelationChangeSetPreviewCommand(
                metadata.getVersion(), Map.of(EntityCapability.TREE, true), List.of());
        MetadataRelationChangeSetPreview preview = previewService.preview(moduleAlias, relationId, proposal);
        assertThat(preview.errors()).isEmpty();

        applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(proposal, preview.proposalFingerprint()));

        assertThat(metadataService.select(metadata.getId()).getCapabilityDeclarations()).contains("TREE", "SORT");
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId())))
                .extracting(MetadataField::getFieldName).contains("parentId", "sortOrder");
        assertThat(columnLength(metadata.getTableName(), "parent_id")).isEqualTo(32);
        assertThat(entityCompiler.compile(metadata.getId()).fields())
                .filteredOn(field -> field.fieldName().equals("parentId"))
                .singleElement().extracting(field -> field.length()).isEqualTo(32);
        verify(refreshCoordinator).activateByMetadataIdNow(metadata.getId());
    }

    @Test
    void shouldRollbackMetadataFieldsAndDdlWhenEnsureFails() {
        MetadataRelationChangeSetPreviewCommand proposal = proposal("rollbackTitle", "rollback_title", fieldSpecAlias(), false);
        MetadataRelationChangeSetPreview preview = previewService.preview(moduleAlias, relationId, proposal);
        schemaEnsureService.failAfterEnsure = true;

        assertThatThrownBy(() -> applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(proposal, preview.proposalFingerprint())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("forced schema failure");

        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId())))
                .extracting(MetadataField::getFieldName).doesNotContain("rollbackTitle");
        assertThat(columnExists(metadata.getTableName(), "rollback_title")).isFalse();
        org.mockito.Mockito.verifyNoInteractions(refreshCoordinator);
    }

    private MetadataRelationChangeSetPreviewCommand proposal(String fieldName, String columnName,
                                                             String specAlias, boolean enable) {
        MetadataField field = new MetadataField();
        field.setFieldName(fieldName);
        field.setColumnName(columnName);
        field.setFieldSpecAlias(specAlias);
        field.setFieldOwnership(MetadataFieldOwnership.BUSINESS);
        field.setFieldForm(MetadataFieldForm.PHYSICAL);
        field.setSystemManaged(Boolean.FALSE);
        field.setTitle(fieldName);
        field.setEnabled(Boolean.TRUE);
        return new MetadataRelationChangeSetPreviewCommand(metadata.getVersion(),
                enable ? Map.of(EntityCapability.ENABLE, true) : Map.of(),
                List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null, field)));
    }

    private String fieldSpecAlias() {
        return stringSpecAlias;
    }

    private boolean columnExists(String table, String column) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select count(*) from information_schema.columns
                     where table_schema = 'public' and table_name = ? and column_name = ?
                     """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) > 0;
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Integer columnLength(String table, String column) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select character_maximum_length from information_schema.columns
                     where table_schema = 'public' and table_name = ? and column_name = ?
                     """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getObject(1, Integer.class);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @EnableMuYunRepositories(basePackageClasses = {FieldSpecDao.class, MetadataDao.class, MetadataFieldDao.class,
            ModuleMetadataRelationDao.class, PlatformModuleDao.class})
    static class TestApplication {
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl())
                .username(postgres.getUsername()).password(postgres.getPassword())
                .driverClassName(postgres.getDriverClassName()).build(); }
        @Bean FieldSpecService fieldSpecService(FieldSpecDao dao) { return new FieldSpecService(dao); }
        @Bean MetadataService metadataService(MetadataDao dao) { return new MetadataService(dao); }
        @Bean PlatformModuleService moduleService() { return mock(PlatformModuleService.class); }
        @Bean ModuleMetadataRelationService relationService(ModuleMetadataRelationDao dao, PlatformModuleService modules,
                                                            MetadataService metadata) { return new ModuleMetadataRelationService(dao, modules, metadata); }
        @Bean MetadataFieldService fieldService(MetadataFieldDao dao, MetadataService metadata, FieldSpecService specs) {
            return new MetadataFieldService(dao, metadata, specs);
        }
        @Bean MetadataFieldConfigService metadataFieldConfigService() { return mock(MetadataFieldConfigService.class); }
        @Bean MetadataFieldDefinitionCompiler fieldCompiler(FieldSpecService specs, MetadataFieldConfigService configs) {
            return new MetadataFieldDefinitionCompiler(specs, configs);
        }
        @Bean PlatformMetadataEntityDefinitionCompiler entityCompiler(MetadataService metadata, MetadataFieldService fields,
                                                                       MetadataFieldDefinitionCompiler compiler) {
            return new PlatformMetadataEntityDefinitionCompiler(metadata, fields, compiler);
        }
        @Bean DynamicSchemaService dynamicSchemaService(net.ximatai.muyun.database.core.IDatabaseOperations<?> operations) {
            return new DynamicSchemaService(operations);
        }
        @Bean TestSchemaEnsureService schemaEnsureService(PlatformMetadataEntityDefinitionCompiler compiler, DynamicSchemaService schema) {
            return new TestSchemaEnsureService(compiler, schema);
        }
        @Bean MetadataRelationChangeSetPreviewService previewService(PlatformModuleService modules,
                                                                      ModuleMetadataRelationService relations,
                                                                      MetadataService metadata, MetadataFieldService fields,
                                                                      FieldSpecService specs) {
            return new MetadataRelationChangeSetPreviewService(modules, relations, metadata, fields, specs);
        }
        @Bean ModuleMetadataCapabilitySnapshotService snapshotService(ModuleMetadataRelationService relations,
                                                                       MetadataService metadata, MetadataFieldService fields) {
            return new ModuleMetadataCapabilitySnapshotService(relations, metadata, fields);
        }
        @Bean PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator() {
            return mock(PlatformDynamicRuntimeRefreshCoordinator.class);
        }
        @Bean MetadataRelationChangeSetApplyService applyService(MetadataRelationChangeSetPreviewService preview,
                                                                  ModuleMetadataRelationService relations,
                                                                  MetadataService metadata, MetadataFieldService fields,
                                                                  TestSchemaEnsureService schema,
                                                                  PlatformDynamicRuntimeRefreshCoordinator refresh,
                                                                  ModuleMetadataCapabilitySnapshotService snapshots) {
            return new MetadataRelationChangeSetApplyService(preview, relations, metadata, fields, schema, refresh, snapshots);
        }
    }

    static class TestSchemaEnsureService extends PlatformMetadataSchemaEnsureService {
        volatile boolean failAfterEnsure;
        TestSchemaEnsureService(PlatformMetadataEntityDefinitionCompiler compiler, DynamicSchemaService schema) { super(compiler, schema); }
        @Override public boolean ensureNow(Metadata metadata) {
            boolean ensured = super.ensureNow(metadata);
            if (failAfterEnsure) throw new IllegalStateException("forced schema failure");
            return ensured;
        }
    }
}
