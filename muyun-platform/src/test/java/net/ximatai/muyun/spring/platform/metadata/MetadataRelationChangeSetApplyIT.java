package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicSchemaGovernanceFacts;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionDao;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageContractType;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariant;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariantDao;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariantService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionDao;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationScopeType;
import net.ximatai.muyun.spring.platform.ui.PresentationConfigurationReferences;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleDao;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.platform.support.TestBeanProviders;
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
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/** Verifies that the real publisher, metadata DAO and PostgreSQL DDL share one transaction. */
@SpringBootTest(classes = MetadataRelationChangeSetApplyIT.TestApplication.class)
class MetadataRelationChangeSetApplyIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Autowired private MetadataRelationChangeSetApplyService applyService;
    @Autowired private MetadataModelChangeSetApplyService modelApply;
    @Autowired private MetadataModelChangeSetPreviewService modelPreview;
    @Autowired private net.ximatai.muyun.spring.platform.ui.PageCompositionSaveService compositionSave;
    @Autowired private MetadataRelationChangeSetPreviewService previewService;
    @Autowired private MetadataService metadataService;
    @Autowired private MetadataFieldService fieldService;
    @Autowired private ModuleMetadataRelationService relationService;
    @Autowired private FieldSpecService fieldSpecService;
    @Autowired private PlatformModuleService moduleService;
    @Autowired private TestSchemaEnsureService schemaEnsureService;
    @Autowired private PlatformMetadataEntityDefinitionCompiler entityCompiler;
    @Autowired private PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator;
    @Autowired private DynamicRecordService recordService;
    @Autowired private DynamicSchemaGovernanceFacts schemaFacts;
    @Autowired private DataSource dataSource;
    @Autowired private net.ximatai.muyun.database.core.IDatabaseOperations<?> operations;
    @Autowired private ModuleMetadataOrchestrationService orchestration;
    @Autowired private MetadataModelDeletionService deletion;

    @Autowired private PlatformPageDefinitionDao pageDao;
    @Autowired private PlatformPresentationVariantDao variantDao;
    @Autowired private PlatformPresentationRevisionDao revisionDao;
    @Autowired private PlatformPresentationRevisionService revisionService;

    private String moduleAlias;
    private String relationId;
    private Metadata metadata;
    private String stringSpecAlias;

    @BeforeEach
    void setUp() {
        reset(moduleService, refreshCoordinator, recordService);
        when(recordService.schemaGovernanceFacts()).thenReturn(schemaFacts);
        schemaEnsureService.failAfterEnsure = false;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        moduleAlias = "crm.change_" + suffix;
        PlatformModule module = new PlatformModule();
        module.setAlias(moduleAlias);
        module.setModuleKind(ModuleKind.DYNAMIC);
        module.setApplicationAlias("crm");
        when(moduleService.select(moduleAlias)).thenReturn(module);

        FieldSpec string = new FieldSpec();
        stringSpecAlias = "string_" + suffix;
        string.setAlias(stringSpecAlias);
        string.setTitle("String");
        string.setFieldType(FieldType.STRING);
        string.setDefaultLength(128);
        fieldSpecService.insert(string);
        if (fieldSpecService.list(Criteria.of().eq("alias", "string")).isEmpty()) {
            FieldSpec standardString = new FieldSpec();
            standardString.setAlias("string");
            standardString.setTitle("String");
            standardString.setFieldType(FieldType.STRING);
            standardString.setDefaultLength(256);
            standardString.setSafeTargetFieldSpecAliases(Set.of("text"));
            fieldSpecService.insert(standardString);
        }
        if (fieldSpecService.list(Criteria.of().eq("alias", "boolean")).isEmpty()) {
            FieldSpec bool = new FieldSpec();
            bool.setAlias("boolean");
            bool.setTitle("Boolean");
            bool.setFieldType(FieldType.BOOLEAN);
            fieldSpecService.insert(bool);
        }
        if (fieldSpecService.list(Criteria.of().eq("alias", "integer")).isEmpty()) {
            FieldSpec integer = new FieldSpec();
            integer.setAlias("integer");
            integer.setTitle("Integer");
            integer.setFieldType(FieldType.INTEGER);
            fieldSpecService.insert(integer);
        }
        if (fieldSpecService.list(Criteria.of().eq("alias", "text")).isEmpty()) {
            FieldSpec text = new FieldSpec();
            text.setAlias("text");
            text.setTitle("Text");
            text.setFieldType(FieldType.TEXT);
            fieldSpecService.insert(text);
        }
        if (fieldSpecService.list(Criteria.of().eq("alias", "datetime")).isEmpty()) {
            FieldSpec datetime = new FieldSpec();
            datetime.setAlias("datetime");
            datetime.setTitle("DateTime");
            datetime.setFieldType(FieldType.TIMESTAMP);
            fieldSpecService.insert(datetime);
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void shouldCommitNewChildAndPageTogetherOrRollbackInvalidPage(boolean invalidPage) throws Exception {
        String key = UUID.randomUUID().toString().replace("-", "");
        String fieldKey = UUID.randomUUID().toString().replace("-", "");
        String alias = net.ximatai.muyun.spring.platform.ui.PageCompositionDraftCompiler.childAlias(key);
        String tree = """
                {"template":"management","templateVersion":1,"nodes":[
                {"slot":"list","title":"列表","fields":%s},
                {"slot":"form","title":"表单","fields":[],"relations":[
                  {"relation":"%s","title":"明细","fields":["field%s"]}]}]}
                """.formatted(invalidPage ? "[\"missing\"]" : "[]", alias, fieldKey);
        var revision = pageRevision(tree);
        revision = revisionService.select(revision.getId());
        if (invalidPage) revision.setTemplateVersion(999);
        var child = new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand.NewChild(key, "明细",
                List.of(new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand.NewField(fieldKey, "说明", "text", true, "shuoMing")));
        var command = new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand(revision, relationId,
                metadataService.select(metadata.getId()).getVersion(), List.of(), List.of(child));
        String id = revision.getId();
        if (invalidPage) {
            assertThatThrownBy(() -> saveComposition(id, command)).hasMessageContaining("not registered");
            assertThat(metadataService.list(Criteria.of().eq("alias", alias))).isEmpty();
            assertThat(columnExists(alias, "id")).isFalse();
            assertThat(revisionService.select(id).getStatus()).isEqualTo(PlatformPresentationRevisionStatus.DRAFT);
        } else {
            saveComposition(id, command);
            var saved = metadataService.list(Criteria.of().eq("alias", alias)).getFirst();
            assertThat(fieldService.list(Criteria.of().eq("metadataId", saved.getId()).eq("fieldName", "shuoMing")))
                    .singleElement().extracting(MetadataField::getRequired).isEqualTo(true);
            assertThat(relationService.list(Criteria.of().eq("metadataId", saved.getId())))
                    .singleElement().extracting(ModuleMetadataRelation::getParentMetadataId).isEqualTo(metadata.getId());
            assertThat(columnExists(saved.getTableName(), "shuo_ming")).isTrue();
            assertThat(revisionService.select(id).getUiTreeJson()).contains("shuoMing").doesNotContain("field" + fieldKey);
            assertThat(revisionService.select(id).getStatus()).isEqualTo(PlatformPresentationRevisionStatus.PUBLISHED);
        }
    }

    @Test
    void shouldCommitPageAndComponentFieldTogetherAndRejectReplay() {
        String key = UUID.randomUUID().toString().replace("-", "");
        String name = "lianXiDianHua2";
        applyNewStringField("lianXiDianHua", "lian_xi_dian_hua");
        var revision = pageRevision("{\"template\":\"management\",\"templateVersion\":1,\"nodes\":[{\"slot\":\"list\",\"title\":\"列表\",\"fields\":[]},{\"slot\":\"form\",\"title\":\"表单\",\"fields\":[\"" + "field" + key + "\"]}]}");
        revision = revisionService.select(revision.getId());
        var command = new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand(revision, relationId,
                metadataService.select(metadata.getId()).getVersion(),
                List.of(new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand.NewField(key, "联系电话", "text", true, "lianXiDianHua")));
        saveComposition(revision.getId(), command);
        assertThat(revisionService.select(revision.getId()).getStatus()).isEqualTo(PlatformPresentationRevisionStatus.PUBLISHED);
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId()).eq("fieldName", name))).hasSize(1);
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId()).eq("fieldName", name)))
                .singleElement().extracting(MetadataField::getRequired).isEqualTo(true);
        assertThat(entityCompiler.compile(metadata.getId()).fields())
                .filteredOn(field -> field.fieldName().equals(name))
                .singleElement().extracting(field -> field.isRequired()).isEqualTo(true);
        assertThat(revisionService.select(revision.getId()).getUiTreeJson()).contains(name).doesNotContain("field" + key);
        assertThat(columnExists(metadata.getTableName(), "lian_xi_dian_hua2")).isTrue();
        String id = revision.getId();
        assertThatThrownBy(() -> saveComposition(id, command)).hasMessageContaining("页面已变化");
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId()).eq("fieldName", name))).hasSize(1);
    }

    @Test
    void shouldPublishComponentUsedOnlyByQuickSearch() {
        String key = UUID.randomUUID().toString().replace("-", "");
        var revision = pageRevision("""
                {"template":"management","templateVersion":2,"mode":"LIST_CARD","quickSearchFields":["field%s"],"nodes":[
                {"slot":"list","title":"列表","fields":[]},{"slot":"form","title":"表单","fields":[]}]}
                """.formatted(key));
        revision = revisionService.select(revision.getId());
        revision.setTemplateVersion(2);
        var command = new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand(revision, relationId,
                metadataService.select(metadata.getId()).getVersion(),
                List.of(new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand.NewField(key, "查询名称", "text", false, "chaXunMingCheng")));
        saveComposition(revision.getId(), command);
        var saved = revisionService.select(revision.getId());
        assertThat(saved.getStatus()).isEqualTo(PlatformPresentationRevisionStatus.PUBLISHED);
        assertThat(saved.getUiTreeJson()).contains("chaXunMingCheng").doesNotContain("field" + key);
        assertThat(columnExists(metadata.getTableName(), "cha_xun_ming_cheng")).isTrue();
    }

    @Test
    void shouldRejectCompositionBasedOnAnOlderMetadataVersionWithoutPublishing() {
        String key = UUID.randomUUID().toString().replace("-", "");
        String name = "field" + key;
        var revision = pageRevision("""
                {"template":"management","templateVersion":1,"nodes":[
                {"slot":"list","title":"列表","fields":[]},
                {"slot":"form","title":"表单","fields":["%s"]}]}
                """.formatted(name));
        var command = new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand(
                revisionService.select(revision.getId()), relationId,
                metadataService.select(metadata.getId()).getVersion() - 1,
                List.of(new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand.NewField(key, "名称", "text")));
        assertThatThrownBy(() -> saveComposition(revision.getId(), command)).hasMessageContaining("元数据版本已变化");
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId()).eq("fieldName", name))).isEmpty();
        assertThat(revisionService.select(revision.getId()).getStatus()).isEqualTo(PlatformPresentationRevisionStatus.DRAFT);
    }

    @Test
    void shouldRollBackComponentFieldAndSchemaWhenPageValidationFails() {
        applyNewStringField("existing", "existing");
        String key = UUID.randomUUID().toString().replace("-", "");
        String name = "lianXiDianHua";
        var revision = pageRevision("{\"template\":\"management\",\"templateVersion\":1,\"nodes\":[{\"slot\":\"list\",\"title\":\"列表\",\"fields\":[]},{\"slot\":\"form\",\"title\":\"表单\",\"fields\":[\"" + "field" + key + "\"]}]}");
        revision = revisionService.select(revision.getId());
        revision.setTemplateVersion(999);
        var command = new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand(revision, relationId,
                metadataService.select(metadata.getId()).getVersion(),
                List.of(new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand.NewField(key, "联系电话", "text", false, name)));
        String id = revision.getId();
        assertThatThrownBy(() -> saveComposition(id, command)).hasMessageContaining("not registered");
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId()).eq("fieldName", name))).isEmpty();
        assertThat(revisionService.select(id).getStatus()).isEqualTo(PlatformPresentationRevisionStatus.DRAFT);
        try (var connection = dataSource.getConnection(); var columns = connection.getMetaData().getColumns(null, "public", metadata.getTableName(), "lian_xi_dian_hua")) {
            assertThat(columns.next()).isFalse();
        } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
    }

    @Test
    void shouldCreateAndDeleteChildWithOnlySystemFieldsUsingRealTables() {
        var child = orchestration.createChildMetadata(moduleAlias, relationId,
                new ModuleChildMetadataCreateCommand("child_" + metadata.getAlias(), "子实体", null, null));
        var fields = fieldService.list(Criteria.of().eq("metadataId", child.metadata().getId()),
                new net.ximatai.muyun.database.core.orm.PageRequest(0, 100));
        assertThat(fields).hasSize(MetadataSystemFieldCatalog.baselineFields().size() + 1);
        assertThat(fields).allSatisfy(field -> {
            assertThat(field.getFieldOwnership()).isEqualTo(MetadataFieldOwnership.STANDARD);
            assertThat(field.getSystemManaged()).isTrue();
            assertThat(columnExists(child.metadata().getTableName(), field.getColumnName())).isTrue();
        });
        var foreignKey = fields.stream().filter(field -> child.relation().getForeignKey().equals(field.getFieldName()))
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> deletion.deleteField(moduleAlias, child.relation().getId(), foreignKey.getId()))
                .hasMessageContaining("子元数据外键不能删除");
        useRealChildRuntime(child.metadata());
        deletion.deleteMetadata(moduleAlias, child.relation().getId());
        assertThat(metadataService.select(child.metadata().getId())).isNull();
        assertThat(relationService.select(child.relation().getId())).isNull();
        assertThat(columnExists(child.metadata().getTableName(), "id")).isFalse();
        assertThat(metadataService.select(metadata.getId())).isNotNull();
        verify(refreshCoordinator, Mockito.atLeastOnce()).scheduleModules(List.of(moduleAlias));
    }

    @Test
    void shouldRejectDeletingSystemOnlyChildWithSoftDeletedBusinessData() {
        var child = orchestration.createChildMetadata(moduleAlias, relationId,
                new ModuleChildMetadataCreateCommand("child_" + metadata.getAlias(), "子实体", null, null));
        var entity = useRealChildRuntime(child.metadata());
        String id = entity.create(entity.newRecord().setValue(child.relation().getForeignKey(), "parent-record"));
        entity.delete(id);
        assertThatThrownBy(() -> deletion.deleteMetadata(moduleAlias, child.relation().getId()))
                .hasMessageContaining("已有业务数据");
        assertThat(columnExists(child.metadata().getTableName(), "id")).isTrue();
    }

    private net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations useRealChildRuntime(Metadata child) {
        var runtime = new net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime(operations);
        runtime.register(net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition.builder(moduleAlias, "测试")
                .entities(List.of(entityCompiler.compile(child))).build());
        var service = new DynamicRecordService(runtime);
        when(recordService.schemaGovernanceFacts()).thenReturn(service.schemaGovernanceFacts());
        return service.entity(moduleAlias, child.getAlias());
    }

    @Test
    void shouldReconcileLegacyChildCatalogueIdempotentlyAndKeepBusinessFieldDeletionGuard() {
        var child = orchestration.createChildMetadata(moduleAlias, relationId,
                new ModuleChildMetadataCreateCommand("child_" + metadata.getAlias(), "子实体", null, null));
        var foreignKey = fieldService.list(Criteria.of().eq("metadataId", child.metadata().getId())
                .eq("fieldName", child.relation().getForeignKey())).getFirst();
        net.ximatai.muyun.spring.ability.PlatformManagedMutationContext.runAsPlatformManaged(() -> {
            foreignKey.setSystemManaged(false);
            foreignKey.setFieldOwnership(MetadataFieldOwnership.BUSINESS);
            fieldService.update(foreignKey);
            var baseline = fieldService.list(Criteria.of().eq("metadataId", child.metadata().getId())
                    .eq("fieldName", "createdAt")).getFirst();
            fieldService.delete(baseline.getId(), baseline.getVersion());
        });
        orchestration.reconcileChildSystemFields(moduleAlias);
        var reconciled = fieldService.select(foreignKey.getId());
        assertThat(reconciled.getSystemManaged()).isTrue();
        assertThat(reconciled.getFieldOwnership()).isEqualTo(MetadataFieldOwnership.STANDARD);
        assertThat(fieldService.list(Criteria.of().eq("metadataId", child.metadata().getId())))
                .hasSize(MetadataSystemFieldCatalog.baselineFields().size() + 1);
        orchestration.reconcileChildSystemFields(moduleAlias);
        assertThat(fieldService.select(foreignKey.getId()).getVersion()).isEqualTo(reconciled.getVersion());
        MetadataField business = new MetadataField();
        business.setMetadataId(child.metadata().getId());
        business.setFieldName("notes");
        business.setColumnName("notes");
        business.setFieldSpecAlias("string");
        business.setTitle("备注");
        fieldService.insert(business);
        assertThatThrownBy(() -> deletion.deleteMetadata(moduleAlias, child.relation().getId()))
                .hasMessageContaining("全部业务字段");
        assertThat(columnExists(child.metadata().getTableName(), "id")).isTrue();
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
        verify(refreshCoordinator).scheduleByMetadataId(metadata.getId());
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
        verify(refreshCoordinator).scheduleByMetadataId(metadata.getId());
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
        Mockito.verifyNoInteractions(refreshCoordinator);
    }

    @Test
    void shouldSwitchFieldSpecAndPhysicalColumnWhenEntityHasNoData() {
        applyNewStringField("note", "note");
        MetadataField saved = field("note");
        recordCountIs(0L);

        applyFieldSpecChange(saved, "text");

        assertThat(field("note").getFieldSpecAlias()).isEqualTo("text");
        assertThat(columnDataType(metadata.getTableName(), "note")).isEqualTo("text");
    }

    @Test
    void shouldAllowTextWideningWhenEntityHasData() {
        applyNewStringField("description", "description");
        MetadataField saved = field("description");
        recordCountIs(1L);

        applyFieldSpecChange(saved, "text");

        assertThat(field("description").getFieldSpecAlias()).isEqualTo("text");
        assertThat(columnDataType(metadata.getTableName(), "description")).isEqualTo("text");
    }

    @Test
    void shouldRejectNonWideningFieldSpecChangeWhenEntityHasData() {
        applyNewStringField("status", "status");
        MetadataField saved = field("status");
        recordCountIs(1L);

        MetadataRelationChangeSetPreview preview = previewService.preview(moduleAlias, relationId,
                fieldSpecChangeProposal(saved, "integer"));

        assertThat(preview.errors()).extracting(MetadataChangeSetValidationIssue::code)
                .contains("FIELD_SPEC_CHANGE_WITH_DATA");
        assertThat(field("status").getFieldSpecAlias()).isEqualTo("string");
        assertThat(columnDataType(metadata.getTableName(), "status")).isEqualTo("character varying");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"list", "form", "group", "titleField", "secondaryField", "quickSearchFields"})
    void shouldProtectPersistedPageFieldsUntilActiveRevisionsReleaseThem(String placement) {
        applyNewStringField("pageField", "page_field");
        MetadataField field = field("pageField");
        String fields = "\"fields\":[{\"field\":\"pageField\",\"props\":{\"label\":\"自定义标题\"}}]";
        PlatformPresentationRevision revision = pageRevision("{\"template\":\"management\",\"templateVersion\":1,\"nodes\":["
                + (placement.equals("group") ? "{\"slot\":\"form\",\"groups\":[{\"group\":\"basic\",\"title\":\"基本信息\"," + fields + "}]}"
                    : "{\"slot\":\"" + placement + "\"," + fields + "}") + "]}");
        if (List.of("titleField", "secondaryField", "quickSearchFields").contains(placement)) {
            revision.setTemplateVersion(2);
            revision.setUiTreeJson(placement.equals("quickSearchFields")
                    ? "{\"templateVersion\":2,\"quickSearchFields\":[\"pageField\"],\"nodes\":[]}"
                    : "{\"templateVersion\":2,\"nodes\":[{\"slot\":\"explorer\",\"" + placement + "\":\"pageField\"}]}");
        }
        for (PlatformPresentationRevisionStatus status : List.of(PlatformPresentationRevisionStatus.DRAFT,
                PlatformPresentationRevisionStatus.PUBLISHED)) {
            revision.setStatus(status);
            revisionDao.updateByIdAndVersion(revision, revision.getVersion());
            assertThatThrownBy(() -> deletion.deleteField(moduleAlias, relationId, field.getId()))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("页面配置验证").hasMessageContaining("修订 v1");
            assertThat(fieldService.select(field.getId())).isNotNull();
            assertThat(columnExists(metadata.getTableName(), "page_field")).isTrue();
        }
        revision.setStatus(PlatformPresentationRevisionStatus.ARCHIVED);
        revisionDao.updateByIdAndVersion(revision, revision.getVersion());
        deletion.deleteField(moduleAlias, relationId, field.getId());
        assertThat(fieldService.select(field.getId())).isNull();
        assertThat(columnExists(metadata.getTableName(), "page_field")).isFalse();
    }

    @Test
    void shouldProtectDirectChildAssociationEvenWithoutBusinessFields() {
        var child = orchestration.createChildMetadata(moduleAlias, relationId,
                new ModuleChildMetadataCreateCommand("page_child_" + UUID.randomUUID().toString().substring(0, 8), "页面子实体", null, null));
        pageRevision("{\"template\":\"management\",\"templateVersion\":1,\"nodes\":[{\"slot\":\"form\",\"relations\":[{\"relation\":\""
                + child.relation().getRelationAlias() + "\",\"fields\":[]}]}]}");
        assertThatThrownBy(() -> deletion.deleteMetadata(moduleAlias, child.relation().getId()))
                .isInstanceOf(PlatformException.class).hasMessageContaining("页面配置验证");
        assertThat(relationService.select(child.relation().getId())).isNotNull();
        assertThat(columnExists(child.metadata().getTableName(), "id")).isTrue();
    }

    @Test
    void shouldIgnoreNamesInDisplayPropertiesAndProtectTheStablePageAnchor() {
        applyNewStringField("pageField", "page_field");
        MetadataField field = field("pageField");
        pageRevision("{\"template\":\"management\",\"templateVersion\":1,\"nodes\":[{\"slot\":\"list\",\"title\":\"pageField\",\"fields\":[]}]}");
        deletion.deleteField(moduleAlias, relationId, field.getId());
        assertThat(fieldService.select(field.getId())).isNull();
        assertThatThrownBy(() -> relationService.delete(relationId, relationService.select(relationId).getVersion()))
                .isInstanceOf(PlatformException.class).hasMessageContaining("页面配置验证").hasMessageContaining("主实体绑定");
    }

    private void saveComposition(String id, net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand command) {
        try (var ignored = net.ximatai.muyun.spring.common.tenant.TenantContext.system("test page composition")) {
            compositionSave.save(id, command);
        }
    }

    private PlatformPresentationRevision pageRevision(String tree) {
        PlatformPageDefinition page = new PlatformPageDefinition();
        page.setId(UUID.randomUUID().toString().replace("-", ""));
        page.setModuleAlias(moduleAlias);
        page.setAlias("management");
        page.setTitle("页面配置验证");
        page.setContractType(PlatformPageContractType.MANAGEMENT);
        page.setMainRelationId(relationId);
        pageDao.insert(page);
        PlatformPresentationVariant variant = new PlatformPresentationVariant();
        variant.setId(UUID.randomUUID().toString().replace("-", ""));
        variant.setPageId(page.getId());
        variant.setTitle("全局 Web");
        variant.setClientType(PlatformPresentationClientType.WEB);
        variant.setScopeType(PlatformPresentationScopeType.GLOBAL);
        variantDao.insert(variant);
        PlatformPresentationRevision revision = new PlatformPresentationRevision();
        revision.setId(UUID.randomUUID().toString().replace("-", ""));
        revision.setVariantId(variant.getId());
        revision.setTitle("测试草稿");
        revision.setRevisionNo(1);
        revision.setTemplateAlias("management");
        revision.setUiTreeJson(tree);
        revisionDao.insert(revision);
        return revision;
    }

    @Test
    void modelCandidateRequiresFreshConfirmationAndPublishesOnlyTheReviewedDefinition() {
        var original = modelProposal("reviewNote", "review_note");
        var preview = modelPreview.preview(moduleAlias, original);
        assertThat(preview.errors()).isEmpty();
        assertThat(columnExists(metadata.getTableName(), "review_note")).isFalse();

        var revised = modelProposal("reviewNote", "review_note");
        revised.relationDrafts().getFirst().fieldDrafts().getFirst().field().setTitle("Reviewed title");
        assertThatThrownBy(() -> modelApply.apply(moduleAlias,
                new MetadataModelChangeSetApplyCommand(revised, preview.proposalFingerprint())))
                .isInstanceOf(PlatformException.class).hasMessageContaining("fingerprint is stale");
        assertThat(columnExists(metadata.getTableName(), "review_note")).isFalse();

        var reviewed = modelPreview.preview(moduleAlias, revised);
        modelApply.apply(moduleAlias, new MetadataModelChangeSetApplyCommand(revised, reviewed.proposalFingerprint()));
        assertThat(field("reviewNote").getTitle()).isEqualTo("Reviewed title");
        assertThat(columnExists(metadata.getTableName(), "review_note")).isTrue();
        assertThatThrownBy(() -> modelApply.apply(moduleAlias,
                new MetadataModelChangeSetApplyCommand(revised, reviewed.proposalFingerprint())))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void modelCandidateRejectsAChangedPersistedBaselineWithoutCreatingItsColumn() {
        var candidate = modelProposal("pendingNote", "pending_note");
        var preview = modelPreview.preview(moduleAlias, candidate);
        applyNewStringField("otherNote", "other_note");
        assertThatThrownBy(() -> modelApply.apply(moduleAlias,
                new MetadataModelChangeSetApplyCommand(candidate, preview.proposalFingerprint())))
                .isInstanceOf(PlatformException.class);
        assertThat(columnExists(metadata.getTableName(), "pending_note")).isFalse();
        assertThat(columnExists(metadata.getTableName(), "other_note")).isTrue();
    }

    @Test
    void fieldPlanRollsBackEveryCandidateAndCanBeRetriedAfterSchemaFailure() {
        var first = proposal("planNote", "plan_note", "string", false);
        var second = proposal("planSummary", "plan_summary", "string", false);
        var plan = new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(
                relationId, first.expectedMetadataVersion(), Map.of(),
                List.of(first.fieldDrafts().getFirst(), second.fieldDrafts().getFirst()))), List.of(), List.of());
        var preview = modelPreview.preview(moduleAlias, plan);
        assertThat(preview.errors()).isEmpty();
        schemaEnsureService.failAfterEnsure = true;
        assertThatThrownBy(() -> modelApply.apply(moduleAlias,
                new MetadataModelChangeSetApplyCommand(plan, preview.proposalFingerprint())))
                .hasMessageContaining("forced schema failure");
        assertThat(columnExists(metadata.getTableName(), "plan_note")).isFalse();
        assertThat(columnExists(metadata.getTableName(), "plan_summary")).isFalse();
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId())))
                .extracting(MetadataField::getFieldName).doesNotContain("planNote", "planSummary");
        assertThat(metadataService.select(metadata.getId()).getVersion()).isEqualTo(first.expectedMetadataVersion());
        Mockito.verifyNoInteractions(refreshCoordinator);

        schemaEnsureService.failAfterEnsure = false;
        var reviewed = modelPreview.preview(moduleAlias, plan);
        modelApply.apply(moduleAlias, new MetadataModelChangeSetApplyCommand(plan, reviewed.proposalFingerprint()));
        assertThat(columnExists(metadata.getTableName(), "plan_note")).isTrue();
        assertThat(columnExists(metadata.getTableName(), "plan_summary")).isTrue();
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId())))
                .extracting(MetadataField::getFieldName).contains("planNote", "planSummary");
    }

    private MetadataModelChangeSetPreviewCommand modelProposal(String fieldName, String columnName) {
        var relation = proposal(fieldName, columnName, "string", false);
        return new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(
                relationId, relation.expectedMetadataVersion(), Map.of(), relation.fieldDrafts())), List.of(), List.of());
    }

    private void applyNewStringField(String fieldName, String columnName) {
        MetadataRelationChangeSetPreviewCommand proposal = proposal(fieldName, columnName, "string", false);
        MetadataRelationChangeSetPreview preview = previewService.preview(moduleAlias, relationId, proposal);
        assertThat(preview.errors()).isEmpty();
        applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(proposal, preview.proposalFingerprint()));
    }

    private void applyFieldSpecChange(MetadataField saved, String fieldSpecAlias) {
        MetadataRelationChangeSetPreviewCommand proposal = fieldSpecChangeProposal(saved, fieldSpecAlias);
        MetadataRelationChangeSetPreview preview = previewService.preview(moduleAlias, relationId, proposal);
        assertThat(preview.errors()).isEmpty();
        applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(proposal, preview.proposalFingerprint()));
    }

    private MetadataRelationChangeSetPreviewCommand fieldSpecChangeProposal(MetadataField saved, String fieldSpecAlias) {
        MetadataField draft = new MetadataField();
        draft.setFieldName(saved.getFieldName());
        draft.setColumnName(saved.getColumnName());
        draft.setFieldSpecAlias(fieldSpecAlias);
        draft.setFieldOwnership(saved.getFieldOwnership());
        draft.setFieldForm(saved.getFieldForm());
        draft.setSystemManaged(saved.getSystemManaged());
        draft.setTitle(saved.getTitle());
        draft.setRequired(saved.getRequired());
        draft.setUniqueField(saved.getUniqueField());
        draft.setIndexed(saved.getIndexed());
        draft.setSortableField(saved.getSortableField());
        draft.setTitleField(saved.getTitleField());
        draft.setEnabled(saved.getEnabled());
        draft.setSortOrder(saved.getSortOrder());
        return new MetadataRelationChangeSetPreviewCommand(metadataService.select(metadata.getId()).getVersion(), Map.of(),
                List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE,
                        saved.getId(), saved.getVersion(), draft)));
    }

    private MetadataField field(String fieldName) {
        return fieldService.list(Criteria.of().eq("metadataId", metadata.getId())).stream()
                .filter(field -> fieldName.equals(field.getFieldName()))
                .findFirst().orElseThrow();
    }

    private void recordCountIs(long count) {
        when(schemaFacts.countPhysicalRecords(eq(moduleAlias), eq(metadata.getAlias()), any(Criteria.class))).thenReturn(count);
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

    private String columnDataType(String table, String column) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select data_type from information_schema.columns
                     where table_schema = 'public' and table_name = ? and column_name = ?
                     """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @org.springframework.context.annotation.Import({PresentationConfigurationReferences.class, ConfigurationReferenceDeletionGuard.class})
    @EnableMuYunRepositories(basePackageClasses = {FieldSpecDao.class, MetadataDao.class, MetadataFieldDao.class,
            ModuleMetadataRelationDao.class, PlatformModuleDao.class, PlatformPageDefinitionDao.class})
    static class TestApplication {
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl())
                .username(postgres.getUsername()).password(postgres.getPassword())
                .driverClassName(postgres.getDriverClassName()).build(); }
        @Bean ModuleMetadataOrchestrationService orchestration(PlatformModuleService modules, MetadataService metadata,
                ModuleMetadataRelationService relations, MetadataFieldService fields, TestSchemaEnsureService schema,
                PlatformDynamicRuntimeRefreshCoordinator refresh) {
            return new ModuleMetadataOrchestrationService(modules, metadata, relations, fields, schema, refresh);
        }
        @Bean MetadataModelDeletionService deletion(ModuleMetadataRelationService relations, MetadataService metadata,
                MetadataFieldService fields, PlatformMetadataEntityDefinitionCompiler compiler, TestSchemaEnsureService schema,
                DynamicRecordService records, PlatformDynamicRuntimeRefreshCoordinator refresh) {
            return new MetadataModelDeletionService(relations, metadata, fields, mock(ModuleMetadataFieldService.class),
                    compiler, schema, records, refresh);
        }
        @Bean FieldSpecService fieldSpecService(FieldSpecDao dao) { return new FieldSpecService(dao, mock(BaseDao.class)); }
        @Bean net.ximatai.muyun.spring.platform.ui.PageCompositionSaveService compositionSave(
                PlatformPresentationRevisionService revisions, PlatformPresentationVariantService variants,
                PlatformPageDefinitionService pages, MetadataRelationChangeSetPreviewService preview,
                MetadataRelationChangeSetApplyService apply, ModuleMetadataRelationService relations, MetadataService metadata, ModuleMetadataOrchestrationService orchestration, MetadataFieldService fields) {
            return new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveService(revisions, variants, pages, preview, apply,
                    new net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionPublishService(revisions, variants, pages,
                            new net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplateCatalog()), relations, metadata, orchestration, fields);
        }
        @Bean MetadataService metadataService(MetadataDao dao) { return new MetadataService(
                dao,
                TestBeanProviders.empty(PlatformMetadataSchemaEnsureService.class),
                Optional.empty(),
                TestBeanProviders.empty(ConfigurationReferenceDeletionGuard.class),
                TestBeanProviders.empty(ModuleMetadataRelationService.class),
                event -> {}); }
        @Bean PlatformModuleService moduleService() { return mock(PlatformModuleService.class); }
        @Bean ModuleMetadataRelationService relationService(ModuleMetadataRelationDao dao, PlatformModuleService modules,
                                                            MetadataService metadata, org.springframework.beans.factory.ObjectProvider<ConfigurationReferenceDeletionGuard> guard) {
            return new ModuleMetadataRelationService(
                    dao,
                    modules,
                    metadata,
                    Optional.empty(),
                    guard,
                    TestBeanProviders.empty(MetadataFieldService.class),
                    event -> {});
        }
        @Bean MetadataFieldService fieldService(MetadataFieldDao dao, MetadataService metadata, FieldSpecService specs,
                org.springframework.beans.factory.ObjectProvider<ConfigurationReferenceDeletionGuard> guard) {
            var empty = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            return new MetadataFieldService(
                    dao,
                    metadata,
                    specs,
                    empty.getBeanProvider(PlatformDynamicRuntimeRefreshCoordinator.class),
                    empty.getBeanProvider(PlatformMetadataSchemaEnsureService.class),
                    guard,
                    empty.getBeanProvider(ModuleMetadataRelationService.class),
                    empty.getBeanProvider(PlatformModuleService.class));
        }
        @Bean PlatformPageDefinitionService pageService(PlatformPageDefinitionDao dao, PlatformModuleService modules, ModuleMetadataRelationService relations) {
            return new PlatformPageDefinitionService(dao, modules, relations);
        }
        @Bean PlatformPresentationVariantService variantService(PlatformPresentationVariantDao dao, PlatformPageDefinitionService pages) {
            return new PlatformPresentationVariantService(dao, pages);
        }
        @Bean PlatformPresentationRevisionService revisionService(PlatformPresentationRevisionDao dao, PlatformPresentationVariantService variants) {
            return new PlatformPresentationRevisionService(dao, variants);
        }
        @Bean ModuleMetadataFieldService moduleFieldService() { return mock(ModuleMetadataFieldService.class); }
        @Bean MetadataFieldConfigService metadataFieldConfigService() { return mock(MetadataFieldConfigService.class); }
        @Bean MetadataFieldDefinitionCompiler fieldCompiler(FieldSpecService specs, MetadataFieldConfigService configs, MetadataFieldService fields) {
            return new MetadataFieldDefinitionCompiler(specs, configs,
                    new MetadataFieldProtectionConfigService(new TestMemoryDao<>(), fields, specs, new TestMemoryDao<>(), Optional.empty()), fields);
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
        @Bean DynamicSchemaGovernanceFacts schemaFacts() { return mock(DynamicSchemaGovernanceFacts.class); }
        @Bean DynamicRecordService recordService(DynamicSchemaGovernanceFacts schemaFacts) {
            DynamicRecordService records = mock(DynamicRecordService.class);
            when(records.schemaGovernanceFacts()).thenReturn(schemaFacts);
            return records;
        }
        @Bean MetadataRelationChangeSetPreviewService previewService(PlatformModuleService modules,
                                                                      ModuleMetadataRelationService relations,
                                                                      MetadataService metadata, MetadataFieldService fields,
                                                                      FieldSpecService specs, DynamicRecordService records) {
            return new MetadataRelationChangeSetPreviewService(modules, relations, metadata, fields, specs,
                    null, null, null, records);
        }
        @Bean ModuleMetadataCapabilitySnapshotService snapshotService(ModuleMetadataRelationService relations,
                                                                       MetadataService metadata, MetadataFieldService fields) {
            return new ModuleMetadataCapabilitySnapshotService(relations, metadata, fields);
        }
        @Bean PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator() {
            return mock(PlatformDynamicRuntimeRefreshCoordinator.class);
        }
        @Bean MetadataModelChangeSetPreviewService modelPreview(PlatformModuleService modules,
                ModuleMetadataRelationService relations, MetadataFieldService fields, MetadataRelationChangeSetPreviewService preview) {
            return new MetadataModelChangeSetPreviewService(modules, relations, fields, preview);
        }
        @Bean MetadataModelChangeSetApplyService modelApply(MetadataModelChangeSetPreviewService preview,
                MetadataRelationChangeSetApplyService relationApply, ModuleMetadataRelationService relations,
                MetadataService metadata, MetadataFieldService fields, TestSchemaEnsureService schema,
                PlatformDynamicRuntimeRefreshCoordinator refresh, ModuleMetadataCapabilitySnapshotService snapshots,
                DynamicRecordService records, FieldSpecService specs) {
            return new MetadataModelChangeSetApplyService(preview, relationApply, relations, metadata, fields,
                    schema, refresh, snapshots, new EmptyMetadataFieldSpecColumnRebuildService(records, specs));
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
