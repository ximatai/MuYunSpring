package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.platform.dictionary.DictionaryFieldValueValidator;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;

import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
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
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.platform.support.TestBeanProviders;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
    @Autowired private MetadataFieldConfigService fieldConfigs;
    @Autowired private ModuleMetadataFieldService moduleFields;
    @Autowired private MetadataFieldReferenceConfigService referenceConfigs;
    @Autowired private MetadataFieldDefinitionCompiler fieldCompiler;
    @Autowired private DictionaryItemService dictionaryItems;
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
    @Autowired private ModuleChildMetadataCreationService childCreation;
    @Autowired private ModuleChildMetadataCreationReceiptDao childReceipts;
    @Autowired private ActionExecutionPolicyService childCreationPermissions;
    @Autowired private PlatformTransactionManager transactionManager;

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
        reset(moduleService, refreshCoordinator, recordService, childCreationPermissions, dictionaryItems);
        when(recordService.schemaGovernanceFacts()).thenReturn(schemaFacts);
        schemaEnsureService.failAfterEnsure = false;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        moduleAlias = "crm.change_" + suffix;
        PlatformModule module = new PlatformModule();
        module.setAlias(moduleAlias);
        module.setTitle("变更测试业务 " + suffix);
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

    @AfterEach
    void resetChildCreationTransactionRuntime() {
        PlatformAbilityRuntime.resetMutationTransactionOperator();
    }

    @Test
    void recordNamePublishesAsStandardTitleAndReportsIncorrectIdentityBeforeAnyWrite() {
        var invalid = proposal("keHuMingCheng", "ke_hu_ming_cheng", stringSpecAlias, false);
        invalid.fieldDrafts().getFirst().field().setTitleField(true);
        var rejected = previewService.preview(moduleAlias, relationId, invalid);
        assertThat(rejected.errors()).extracting(MetadataChangeSetValidationIssue::message)
                .anyMatch(message -> message.contains("字段名或物理列名") && !message.contains("TEXT"));
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId())))
                .extracting(MetadataField::getFieldName).doesNotContain("keHuMingCheng");
        assertThat(columnExists(metadata.getTableName(), "ke_hu_ming_cheng")).isFalse();

        var corrected = proposal("title", "title", stringSpecAlias, false);
        var name = corrected.fieldDrafts().getFirst().field();
        name.setTitle("客户名称");
        name.setTitleField(true);
        name.setRequired(true);
        name.setUniqueField(false);
        var checked = previewService.preview(moduleAlias, relationId, corrected);
        assertThat(checked.errors()).isEmpty();
        applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(corrected, checked.proposalFingerprint()));
        assertThat(field("title")).satisfies(saved -> {
            assertThat(saved.getColumnName()).isEqualTo("title");
            assertThat(saved.getTitle()).isEqualTo("客户名称");
            assertThat(saved.getTitleField()).isTrue();
            assertThat(saved.getUniqueField()).isFalse();
        });
        assertThat(columnExists(metadata.getTableName(), "title")).isTrue();
    }

    @Test
    void fixedDefaultPublishesThroughRealConfigAndCompiledRelationBehaviorAndCanBeCleared() {
        var proposal = withFixedDefault(proposal("quantity", "quantity", "integer", false), "1", null);
        var checked = previewService.preview(moduleAlias, relationId, proposal);
        assertThat(checked.errors()).isEmpty();
        applyService.apply(moduleAlias, relationId, new MetadataRelationChangeSetApplyCommand(proposal, checked.proposalFingerprint()));
        var saved = fieldService.list(Criteria.of().eq("metadataId", metadata.getId()).eq("fieldName", "quantity")).getFirst();
        var config = fieldConfigs.findRelationOverride(saved.getId(), relationId);
        assertThat(config.getDefaultValue()).isEqualTo("1");
        assertThat(fieldCompiler.compile(saved, relationId).behavior().defaultValue()).isEqualTo("1");
        var update = new MetadataRelationChangeSetPreviewCommand(metadataService.select(metadata.getId()).getVersion(), Map.of(),
                List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE, saved.getId(), saved.getVersion(), saved,
                        new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.BASIC, null, null, null,
                                new MetadataFieldFixedDefaultDraft(null, config.getVersion())))));
        var removal = previewService.preview(moduleAlias, relationId, update);
        assertThat(removal.errors()).isEmpty();
        config.setDefaultValue("2");
        fieldConfigs.update(config);
        assertThatThrownBy(() -> applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(update, removal.proposalFingerprint())))
                .hasMessageContaining("validation failed");
        var latest = fieldConfigs.findRelationOverride(saved.getId(), relationId);
        var refreshedUpdate = withFixedDefault(update, null, latest.getVersion());
        var latestRemoval = previewService.preview(moduleAlias, relationId, refreshedUpdate);
        applyService.apply(moduleAlias, relationId, new MetadataRelationChangeSetApplyCommand(refreshedUpdate, latestRemoval.proposalFingerprint()));
        assertThat(fieldConfigs.findRelationOverride(saved.getId(), relationId).getDefaultValue()).isNull();
        assertThat(fieldCompiler.compile(fieldService.select(saved.getId()), relationId).behavior().defaultValue()).isNull();
    }

    @Test
    void rejectedDefaultsNeverPublishAndValidDictionaryDefaultsFillRealInsertedRecords() {
        var tooLong = withFixedDefault(proposal("code", "code", stringSpecAlias, false), "X".repeat(129), null);
        var rejected = previewService.preview(moduleAlias, relationId, tooLong);
        assertThat(rejected.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIXED_DEFAULT");
        assertThatThrownBy(() -> applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(tooLong, rejected.proposalFingerprint())))
                .hasMessageContaining("validation failed");
        assertThat(columnExists(metadata.getTableName(), "code")).isFalse();

        for (String alias : List.of("json", "json_set")) {
            if (fieldSpecService.select(alias) == null) {
                FieldSpec json = new FieldSpec(); json.setAlias(alias); json.setTitle(alias); json.setFieldType(FieldType.JSON);
                fieldSpecService.insert(json);
            }
        }
        when(dictionaryItems.resolveEnabledItem("crm", "state", "NEW"))
                .thenReturn(new net.ximatai.muyun.spring.platform.dictionary.DictionaryItem());
        for (String name : List.of("status", "tags", "genericTags")) {
            boolean multiple = !"status".equals(name);
            var original = proposal(name, "genericTags".equals(name) ? "generic_tags" : name,
                    "genericTags".equals(name) ? "json" : multiple ? "json_set" : stringSpecAlias, false);
            var draft = original.fieldDrafts().getFirst();
            MetadataFieldConfig dictionary = new MetadataFieldConfig(); dictionary.setDictionaryApplicationAlias("crm");
            dictionary.setDictionaryCategoryAlias("state"); dictionary.setSelectionMode(multiple
                    ? net.ximatai.muyun.spring.common.option.OptionSelectionMode.MULTIPLE
                    : net.ximatai.muyun.spring.common.option.OptionSelectionMode.SINGLE);
            var command = new MetadataRelationChangeSetPreviewCommand(metadata.getVersion(), Map.of(), List.of(
                    new MetadataFieldChangeSetDraft(draft.operation(), null, null, draft.field(),
                            new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.DICTIONARY, null, null, dictionary,
                                    new MetadataFieldFixedDefaultDraft(multiple ? "[\"NEW\"]" : "NEW", null)))));
            var checked = previewService.preview(moduleAlias, relationId, command);
            assertThat(checked.errors()).isEmpty();
            applyService.apply(moduleAlias, relationId, new MetadataRelationChangeSetApplyCommand(command, checked.proposalFingerprint()));
            metadata = metadataService.select(metadata.getId());
        }
        var base = entityCompiler.compile(metadata);
        var scopedFields = base.fields().stream().map(definition ->
                fieldCompiler.compile(field(definition.code()), relationId)).toList();
        var scoped = new net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition(base.alias(), base.schemaName(),
                base.tableName(), base.name(), scopedFields, base.capabilities());
        try (var runtime = net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime.builder(operations)
                .fieldValueValidator(new DictionaryFieldValueValidator(dictionaryItems)).build()) {
            runtime.register(new net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition(moduleAlias, "Initial values", List.of(scoped)));
            DynamicRecordService records = new DynamicRecordService(runtime);
            var candidate = records.newRecord(moduleAlias, scoped.alias());
            String id = records.create(moduleAlias, scoped.alias(), candidate);
            var stored = records.select(moduleAlias, scoped.alias(), id);
            assertThat(stored.getValue("status")).isEqualTo("NEW");
            assertThat(candidate.getValue("tags")).isEqualTo(List.of("NEW"));
            assertThat(stored.getValue("tags")).isEqualTo(List.of("NEW"));
            assertThat(stored.getValue("genericTags")).isEqualTo(List.of("NEW"));
        }
    }

    @Test
    void addingDictionaryToExistingBehaviorUsesBindingVersionAndPreservesScopeDeclarations() {
        when(dictionaryItems.resolveEnabledItem("crm", "state", "NEW"))
                .thenReturn(new net.ximatai.muyun.spring.platform.dictionary.DictionaryItem());
        for (boolean relationScoped : List.of(false, true)) {
            metadata = metadataService.select(metadata.getId());
            String name = relationScoped ? "localStatus" : "baseStatus";
            applyNewStringField(name, relationScoped ? "local_status" : "base_status");
            var saved = field(name);
            var behavior = new MetadataFieldConfig();
            behavior.setMetadataFieldId(saved.getId());
            behavior.setRelationId(relationScoped ? relationId : null);
            behavior.setDefaultValue("NEW");
            behavior.setCopyable(false);
            fieldConfigs.insert(behavior);
            var candidate = updateProperty(saved, new MetadataFieldPropertyDraft(
                    MetadataFieldPropertyKind.DICTIONARY, null, null, dictionaryBinding("state")));
            var checked = previewService.preview(moduleAlias, relationId, candidate);
            assertThat(checked.errors()).isEmpty();
            applyService.apply(moduleAlias, relationId,
                    new MetadataRelationChangeSetApplyCommand(candidate, checked.proposalFingerprint()));
            var published = fieldConfigs.findRelationOverride(saved.getId(), relationId);
            assertThat(published.getDictionaryCategoryAlias()).isEqualTo("state");
            assertThat(published.getDefaultValue()).isEqualTo(relationScoped ? "NEW" : null);
            assertThat(published.getCopyable()).isEqualTo(relationScoped ? Boolean.FALSE : null);
            assertThat(fieldCompiler.compile(field(name), relationId).behavior().defaultValue()).isEqualTo("NEW");
            assertThat(fieldCompiler.compile(field(name), relationId).behavior().copyable()).isFalse();
            var stale = updateProperty(field(name), new MetadataFieldPropertyDraft(
                    MetadataFieldPropertyKind.DICTIONARY, published.getVersion(), null, dictionaryBinding("state")));
            var reviewed = previewService.preview(moduleAlias, relationId, stale);
            published.setDefaultValue("NEW");
            fieldConfigs.update(published);
            assertThatThrownBy(() -> applyService.apply(moduleAlias, relationId,
                    new MetadataRelationChangeSetApplyCommand(stale, reviewed.proposalFingerprint())))
                    .hasMessageContaining("validation failed");
        }
    }

    @Test
    void defaultPublicationKeepsInheritedBehaviorLiveAndPreservesExistingOverrides() {
        applyNewStringField("code", "code");
        var saved = field("code");
        var base = new MetadataFieldConfig();
        base.setMetadataFieldId(saved.getId());
        base.setValidationRegex("[A-Z]+");
        base.setTextNormalization(net.ximatai.muyun.spring.common.model.constraint.TextNormalization.TRIM);
        base.setCopyable(false);
        base.setQueryable(false);
        fieldConfigs.insert(base);
        var candidate = updateProperty(saved, new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.BASIC,
                null, null, null, new MetadataFieldFixedDefaultDraft(" ABC ", base.getVersion())));
        var checked = previewService.preview(moduleAlias, relationId, candidate);
        assertThat(checked.errors()).isEmpty();
        applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(candidate, checked.proposalFingerprint()));
        var own = fieldConfigs.findRelationOverride(saved.getId(), relationId);
        assertThat(own.getDefaultValue()).isEqualTo(" ABC ");
        assertThat(own.getValidationRegex()).isNull();
        assertThat(own.getTextNormalization()).isNull();
        assertThat(own.getCopyable()).isNull();
        assertThat(own.getQueryable()).isNull();
        base.setValidationRegex("[A-Z0-9]+");
        base.setTextNormalization(net.ximatai.muyun.spring.common.model.constraint.TextNormalization.TRIM_TO_NULL);
        base.setCopyable(true);
        base.setQueryable(true);
        fieldConfigs.update(base);
        var compiled = fieldCompiler.compile(field("code"), relationId);
        assertThat(compiled.behavior().validationRegex()).isEqualTo("[A-Z0-9]+");
        assertThat(compiled.behavior().writeRules().textNormalization())
                .isEqualTo(net.ximatai.muyun.spring.common.model.constraint.TextNormalization.TRIM_TO_NULL);
        assertThat(compiled.behavior().copyable()).isTrue();
        assertThat(compiled.queryDefinition().queryable()).isTrue();
        own.setCopyable(false);
        fieldConfigs.update(own);
        var revised = updateProperty(field("code"), new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.BASIC,
                null, null, null, new MetadataFieldFixedDefaultDraft("ABC1", own.getVersion())));
        var revisedPreview = previewService.preview(moduleAlias, relationId, revised);
        assertThat(revisedPreview.errors()).isEmpty();
        applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(revised, revisedPreview.proposalFingerprint()));
        assertThat(fieldCompiler.compile(field("code"), relationId).behavior().copyable()).isFalse();
        assertThat(fieldConfigs.findRelationOverride(saved.getId(), relationId).getValidationRegex()).isNull();
    }

    @Test
    void inheritedDictionaryBindingVersionRemainsIndependentOfDefaultOverrideVersion() {
        when(dictionaryItems.resolveEnabledItem("crm", "state", "NEW"))
                .thenReturn(new net.ximatai.muyun.spring.platform.dictionary.DictionaryItem());
        applyNewStringField("status", "status");
        var saved = field("status");
        var base = dictionaryBinding("state");
        base.setMetadataFieldId(saved.getId());
        fieldConfigs.insert(base);
        var own = new MetadataFieldConfig();
        own.setMetadataFieldId(saved.getId()); own.setRelationId(relationId); own.setDefaultValue("NEW");
        fieldConfigs.insert(own);
        own.setDefaultValue("NEW"); fieldConfigs.update(own);
        assertThat(base.getVersion()).isNotEqualTo(own.getVersion());
        var summaries = new ModuleMetadataFieldPropertySummaryService(relationService, fieldService,
                referenceConfigs, fieldConfigs, mock(ModuleMetadataFieldService.class));
        var summary = summaries.list(moduleAlias, relationId).stream()
                .filter(item -> item.fieldId().equals(saved.getId())).findFirst().orElseThrow();
        assertThat(summary.kind()).isEqualTo(MetadataFieldPropertyKind.DICTIONARY);
        assertThat(summary.bindingVersion()).isEqualTo(base.getVersion());
        assertThat(summary.fixedDefault().configVersion()).isEqualTo(own.getVersion());
        assertThat(fieldCompiler.compile(saved, relationId).dictionaryBinding().categoryAlias()).isEqualTo("state");
        var candidate = updateProperty(saved, new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.DICTIONARY,
                summary.bindingVersion(), null, dictionaryBinding("state"),
                new MetadataFieldFixedDefaultDraft("NEW", summary.fixedDefault().configVersion())));
        var checked = previewService.preview(moduleAlias, relationId, candidate);
        assertThat(checked.errors()).isEmpty();
        base.setDefaultValue("NEW"); fieldConfigs.update(base);
        assertThat(previewService.preview(moduleAlias, relationId, candidate).errors())
                .extracting(MetadataChangeSetValidationIssue::code).contains("STALE_FIELD_PROPERTY_VERSION");
        assertThatThrownBy(() -> applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(candidate, checked.proposalFingerprint())))
                .hasMessageContaining("validation failed");
        var current = updateProperty(saved, new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.DICTIONARY,
                base.getVersion(), null, dictionaryBinding("state"),
                new MetadataFieldFixedDefaultDraft("NEW", own.getVersion())));
        var currentPreview = previewService.preview(moduleAlias, relationId, current);
        assertThat(currentPreview.errors()).isEmpty();
        applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(current, currentPreview.proposalFingerprint()));
        assertThat(fieldCompiler.compile(field("status"), relationId).behavior().defaultValue()).isEqualTo("NEW");
    }

    @Test
    void retainedDefaultIsPrecheckedAgainstNewDictionaryWithoutPartialModelPublication() {
        when(dictionaryItems.resolveEnabledItem("crm", "state", "NEW"))
                .thenReturn(new net.ximatai.muyun.spring.platform.dictionary.DictionaryItem());
        applyNewStringField("status", "status");
        var saved = field("status");
        var own = dictionaryBinding("state");
        own.setMetadataFieldId(saved.getId()); own.setRelationId(relationId); own.setDefaultValue("NEW");
        fieldConfigs.insert(own);
        var rejected = updateProperty(saved, new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.DICTIONARY,
                own.getVersion(), null, dictionaryBinding("replacement")));
        var checked = previewService.preview(moduleAlias, relationId, rejected);
        assertThat(checked.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIXED_DEFAULT");
        var sibling = proposal("sibling", "sibling", stringSpecAlias, false).fieldDrafts().getFirst();
        var model = new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(
                relationId, rejected.expectedMetadataVersion(), Map.of(),
                List.of(sibling, rejected.fieldDrafts().getFirst()))), List.of(), List.of());
        var modelChecked = modelPreview.preview(moduleAlias, model);
        assertThat(modelChecked.valid()).isFalse();
        assertThatThrownBy(() -> modelApply.apply(moduleAlias,
                new MetadataModelChangeSetApplyCommand(model, modelChecked.proposalFingerprint())))
                .hasMessageContaining("validation failed");
        assertThat(columnExists(metadata.getTableName(), "sibling")).isFalse();
        assertThat(fieldConfigs.findRelationOverride(saved.getId(), relationId).getDictionaryCategoryAlias()).isEqualTo("state");
        assertThat(fieldService.select(saved.getId()).getVersion()).isEqualTo(saved.getVersion());
    }

    @Test
    void retainedDefaultIsPrecheckedWhenOnlyFieldSpecificationChangesAndBaselineIsConfirmed() {
        applyNewStringField("code", "code");
        var saved = field("code");
        var base = new MetadataFieldConfig();
        base.setMetadataFieldId(saved.getId()); base.setDefaultValue("ABC");
        fieldConfigs.insert(base);
        var invalidType = fieldSpecChangeProposal(saved, "integer");
        assertThat(previewService.preview(moduleAlias, relationId, invalidType).errors())
                .extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIXED_DEFAULT");
        assertThat(fieldService.select(saved.getId()).getFieldSpecAlias()).isEqualTo("string");
        var unchangedType = fieldSpecChangeProposal(saved, "string");
        var checked = previewService.preview(moduleAlias, relationId, unchangedType);
        assertThat(checked.errors()).isEmpty();
        base.setDefaultValue("DEF"); fieldConfigs.update(base);
        assertThat(previewService.preview(moduleAlias, relationId, unchangedType).proposalFingerprint())
                .isNotEqualTo(checked.proposalFingerprint());
        assertThatThrownBy(() -> applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(unchangedType, checked.proposalFingerprint())))
                .hasMessageContaining("fingerprint is stale");
    }

    @Test
    void currentRelationDefaultCannotHideAnInvalidSharedBaseAndDirectFieldWritesUseTheSameCheck() {
        applyNewStringField("amount", "amount");
        var saved = field("amount");
        var base = new MetadataFieldConfig();
        base.setMetadataFieldId(saved.getId()); base.setDefaultValue("ABC"); fieldConfigs.insert(base);
        var own = new MetadataFieldConfig();
        own.setMetadataFieldId(saved.getId()); own.setRelationId(relationId); own.setDefaultValue("1"); fieldConfigs.insert(own);
        var candidate = fieldSpecChangeProposal(saved, "integer");
        var checked = previewService.preview(moduleAlias, relationId, candidate);
        assertThat(checked.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIXED_DEFAULT");
        assertThatThrownBy(() -> applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(candidate, checked.proposalFingerprint())))
                .hasMessageContaining("validation failed");
        var direct = candidate.fieldDrafts().getFirst().field();
        direct.setId(saved.getId()); direct.setMetadataId(metadata.getId()); direct.setVersion(saved.getVersion());
        assertThatThrownBy(() -> fieldService.update(direct)).isInstanceOf(IllegalArgumentException.class);
        assertThat(field("amount").getFieldSpecAlias()).isEqualTo("string");
        assertThat(field("amount").getVersion()).isEqualTo(saved.getVersion());
    }

    @Test
    void sharedFieldChangesValidateOtherModuleOverridesAndLegacyAndProtectTheirReviewedBaselines() {
        applyNewStringField("amount", "amount");
        var saved = field("amount");
        var base = new MetadataFieldConfig();
        base.setMetadataFieldId(saved.getId()); base.setDefaultValue("0"); fieldConfigs.insert(base);
        var own = new MetadataFieldConfig();
        own.setMetadataFieldId(saved.getId()); own.setRelationId(relationId); own.setDefaultValue("1"); fieldConfigs.insert(own);
        String otherRelation = anotherModuleRelation();
        var other = new MetadataFieldConfig();
        other.setMetadataFieldId(saved.getId()); other.setRelationId(otherRelation); other.setDefaultValue("ABC"); fieldConfigs.insert(other);
        useRealChildRuntime(metadata);
        var candidate = fieldSpecChangeProposal(saved, "integer");
        assertThat(previewService.preview(moduleAlias, relationId, candidate).errors())
                .extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIXED_DEFAULT");
        other.setDefaultValue("2"); fieldConfigs.update(other);
        var checked = previewService.preview(moduleAlias, relationId, candidate);
        assertThat(checked.errors()).isEmpty();
        var legacy = new ModuleMetadataField();
        legacy.setRelationId(otherRelation); legacy.setMetadataFieldId(saved.getId()); legacy.setDefaultValue("ABC");
        legacy.setTitle("Other amount");
        moduleFields.insert(legacy);
        assertThat(previewService.preview(moduleAlias, relationId, candidate).errors())
                .extracting(MetadataChangeSetValidationIssue::code).contains("INVALID_FIXED_DEFAULT");
        var direct = candidate.fieldDrafts().getFirst().field();
        direct.setId(saved.getId()); direct.setMetadataId(metadata.getId()); direct.setVersion(saved.getVersion());
        assertThatThrownBy(() -> fieldService.update(direct)).isInstanceOf(IllegalArgumentException.class);
        assertThat(field("amount").getFieldSpecAlias()).isEqualTo("string");
        legacy.setDefaultValue("3"); moduleFields.update(legacy);
        var reviewed = previewService.preview(moduleAlias, relationId, candidate);
        assertThat(reviewed.errors()).isEmpty();
        legacy.setDefaultValue("4"); moduleFields.update(legacy);
        var latest = previewService.preview(moduleAlias, relationId, candidate);
        assertThat(latest.errors()).isEmpty();
        assertThat(latest.proposalFingerprint()).isNotEqualTo(reviewed.proposalFingerprint());
        assertThatThrownBy(() -> applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(candidate, reviewed.proposalFingerprint())))
                .hasMessageContaining("fingerprint is stale");
        var model = modelProposal(candidate);
        var modelChecked = modelPreview.preview(moduleAlias, model);
        assertThat(modelChecked.errors()).isEmpty();
        modelApply.apply(moduleAlias, new MetadataModelChangeSetApplyCommand(model, modelChecked.proposalFingerprint()));
        assertThat(field("amount").getFieldSpecAlias()).isEqualTo("integer");
        var otherCompiled = fieldCompiler.compile(field("amount"), otherRelation, moduleFields.select(legacy.getId()));
        assertThat(otherCompiled.behavior().defaultValue()).isEqualTo("4");
    }

    @Test
    void fieldSpecificationAndCurrentDefaultCanBeRepairedInOnePublication() {
        applyNewStringField("amount", "amount");
        var saved = field("amount");
        var base = new MetadataFieldConfig();
        base.setMetadataFieldId(saved.getId()); base.setDefaultValue("0"); fieldConfigs.insert(base);
        var own = new MetadataFieldConfig();
        own.setMetadataFieldId(saved.getId()); own.setRelationId(relationId); own.setDefaultValue("ABC"); fieldConfigs.insert(own);
        useRealChildRuntime(metadata);
        var candidate = withFixedDefault(fieldSpecChangeProposal(saved, "integer"), "1", own.getVersion());
        var checked = previewService.preview(moduleAlias, relationId, candidate);
        assertThat(checked.errors()).isEmpty();
        var model = modelProposal(candidate);
        var modelChecked = modelPreview.preview(moduleAlias, model);
        assertThat(modelChecked.errors()).isEmpty();
        modelApply.apply(moduleAlias, new MetadataModelChangeSetApplyCommand(model, modelChecked.proposalFingerprint()));
        assertThat(field("amount").getFieldSpecAlias()).isEqualTo("integer");
        assertThat(fieldCompiler.compile(field("amount"), relationId).behavior().defaultValue()).isEqualTo("1");
        var compiled = entityCompiler.compile(metadataService.select(metadata.getId()));
        var scoped = new net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition(compiled.alias(), compiled.schemaName(),
                compiled.tableName(), compiled.name(), compiled.fields().stream()
                .map(definition -> fieldCompiler.compile(field(definition.code()), relationId)).toList(), compiled.capabilities());
        try (var runtime = net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime.builder(operations).build()) {
            runtime.register(new net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition(moduleAlias, "Repaired amount", List.of(scoped)));
            var records = new DynamicRecordService(runtime);
            String id = records.create(moduleAlias, scoped.alias(), records.newRecord(moduleAlias, scoped.alias()));
            assertThat(records.select(moduleAlias, scoped.alias(), id).getValue("amount")).isEqualTo(1);
        }
    }

    @Test
    void localDefaultConfirmationIgnoresOtherModuleBehaviorChanges() {
        applyNewStringField("amount", "amount");
        var saved = field("amount");
        var own = new MetadataFieldConfig();
        own.setMetadataFieldId(saved.getId()); own.setRelationId(relationId); own.setDefaultValue("1"); fieldConfigs.insert(own);
        var other = new MetadataFieldConfig();
        other.setMetadataFieldId(saved.getId()); other.setRelationId(anotherModuleRelation()); other.setDefaultValue("2"); fieldConfigs.insert(other);
        var candidate = withFixedDefault(fieldSpecChangeProposal(saved, "string"), "3", own.getVersion());
        var checked = previewService.preview(moduleAlias, relationId, candidate);
        assertThat(checked.errors()).isEmpty();
        other.setDefaultValue("4"); fieldConfigs.update(other);
        var refreshed = previewService.preview(moduleAlias, relationId, candidate);
        assertThat(refreshed.errors()).isEmpty();
        assertThat(refreshed.proposalFingerprint()).isEqualTo(checked.proposalFingerprint());
        applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(candidate, checked.proposalFingerprint()));
        assertThat(fieldCompiler.compile(field("amount"), relationId).behavior().defaultValue()).isEqualTo("3");
    }

    @Test
    void sharedFieldChangesRejectOtherModuleDictionaryTypesEvenWithoutAnInitialValue() {
        applyNewStringField("status", "status");
        var saved = field("status");
        String otherRelation = anotherModuleRelation();
        var other = dictionaryBinding("state");
        other.setMetadataFieldId(saved.getId()); other.setRelationId(otherRelation); fieldConfigs.insert(other);
        var candidate = fieldSpecChangeProposal(saved, "integer");
        assertThat(previewService.preview(moduleAlias, relationId, candidate).errors())
                .extracting(MetadataChangeSetValidationIssue::message).anyMatch(message -> message.contains("requires string field"));
        var direct = candidate.fieldDrafts().getFirst().field();
        direct.setId(saved.getId()); direct.setMetadataId(metadata.getId()); direct.setVersion(saved.getVersion());
        assertThatThrownBy(() -> fieldService.update(direct)).hasMessageContaining("requires string field");
        assertThat(other.getSelectionMode()).isEqualTo(OptionSelectionMode.SINGLE);
        assertThat(other.getDefaultValue()).isNull();
        fieldConfigs.delete(other.getId());
        var legacy = new ModuleMetadataField();
        legacy.setRelationId(otherRelation); legacy.setMetadataFieldId(saved.getId()); legacy.setTitle("Other status");
        legacy.setDictionaryApplicationAlias("crm"); legacy.setDictionaryCategoryAlias("state"); moduleFields.insert(legacy);
        assertThat(previewService.preview(moduleAlias, relationId, candidate).errors())
                .extracting(MetadataChangeSetValidationIssue::message).anyMatch(message -> message.contains("requires string field"));
        assertThatThrownBy(() -> fieldService.update(direct)).hasMessageContaining("requires string field");
        assertThat(field("status").getFieldSpecAlias()).isEqualTo("string");
        assertThat(moduleFields.select(legacy.getId()).getDefaultValue()).isNull();
    }

    private MetadataModelChangeSetPreviewCommand modelProposal(MetadataRelationChangeSetPreviewCommand candidate) {
        return new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(
                relationId, candidate.expectedMetadataVersion(), candidate.capabilitySelections(), candidate.fieldDrafts())),
                List.of(), List.of());
    }

    private String anotherModuleRelation() {
        String alias = moduleAlias + "_other";
        var module = new PlatformModule();
        module.setAlias(alias); module.setApplicationAlias("crm"); module.setModuleKind(ModuleKind.DYNAMIC);
        module.setTitle("共享模型业务");
        when(moduleService.select(alias)).thenReturn(module);
        var relation = new ModuleMetadataRelation();
        relation.setModuleAlias(alias); relation.setMetadataId(metadata.getId());
        relation.setRelationRole(RelationRole.MAIN); relation.setRelationAlias(metadata.getAlias()); relation.setTitle("Other use");
        return relationService.insert(relation);
    }

    private MetadataFieldConfig dictionaryBinding(String category) {
        var config = new MetadataFieldConfig();
        config.setDictionaryApplicationAlias("crm"); config.setDictionaryCategoryAlias(category);
        config.setSelectionMode(net.ximatai.muyun.spring.common.option.OptionSelectionMode.SINGLE);
        return config;
    }

    private MetadataRelationChangeSetPreviewCommand updateProperty(MetadataField saved, MetadataFieldPropertyDraft property) {
        return new MetadataRelationChangeSetPreviewCommand(metadataService.select(metadata.getId()).getVersion(), Map.of(),
                List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE,
                        saved.getId(), saved.getVersion(), saved, property)));
    }

    @Test
    void governedFieldDeletionRemovesItsOwnDefaultDeclarationAndPhysicalColumn() {
        var proposal = withFixedDefault(proposal("quantity", "quantity", "integer", false), "1", null);
        var checked = previewService.preview(moduleAlias, relationId, proposal);
        applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(proposal, checked.proposalFingerprint()));
        var saved = field("quantity");
        assertThat(fieldConfigs.findRelationOverride(saved.getId(), relationId)).isNotNull();
        deletion.deleteField(moduleAlias, relationId, saved.getId());
        assertThat(fieldService.select(saved.getId())).isNull();
        assertThat(fieldConfigs.findRelationOverride(saved.getId(), relationId)).isNull();
        assertThat(columnExists(metadata.getTableName(), "quantity")).isFalse();
    }

    @Test
    void governedFieldDeletionRemovesItsOwnReferenceDeclarationAndPhysicalColumn() {
        applyNewStringField("referenceTargetName", "reference_target_name");
        metadata = metadataService.select(metadata.getId());
        applyNewStringField("ownerId", "owner_id");
        var saved = field("ownerId");
        var reference = referenceDeclaration(saved, relationId);
        referenceConfigs.insert(reference);
        assertThat(referenceConfigs.findForRelation(saved.getId(), relationId)).isNotNull();

        deletion.deleteField(moduleAlias, relationId, saved.getId());

        assertThat(fieldService.select(saved.getId())).isNull();
        assertThat(referenceConfigs.select(reference.getId())).isNull();
        assertThat(columnExists(metadata.getTableName(), "owner_id")).isFalse();
        assertThat(fieldService.select(field("referenceTargetName").getId())).isNotNull();
    }

    @Test
    void governedFieldDeletionRollsBackOwnReferenceWhenAnInheritedDeclarationStillUsesTheField() {
        applyNewStringField("referenceTargetName", "reference_target_name");
        metadata = metadataService.select(metadata.getId());
        applyNewStringField("ownerId", "owner_id");
        var saved = field("ownerId");
        var own = referenceDeclaration(saved, relationId);
        referenceConfigs.insert(own);
        var inherited = referenceDeclaration(saved, null);
        referenceConfigs.insert(inherited);

        assertThatThrownBy(() -> deletion.deleteField(moduleAlias, relationId, saved.getId()))
                .isInstanceOf(PlatformException.class).hasMessageContaining("字段引用配置");

        assertThat(fieldService.select(saved.getId())).isNotNull();
        assertThat(referenceConfigs.select(own.getId())).isNotNull();
        assertThat(referenceConfigs.select(inherited.getId())).isNotNull();
        assertThat(columnExists(metadata.getTableName(), "owner_id")).isTrue();
    }

    private MetadataFieldReferenceConfig referenceDeclaration(MetadataField source, String scope) {
        var reference = new MetadataFieldReferenceConfig();
        reference.setMetadataFieldId(source.getId());
        reference.setRelationId(scope);
        reference.setTargetMetadataId(metadata.getId());
        reference.setTargetLabelField("referenceTargetName");
        return reference;
    }

    @Test
    void governedFieldDeletionRollsBackOwnDefaultWhenAnInheritedConfigurationStillReferencesIt() {
        var proposal = withFixedDefault(proposal("quantity", "quantity", "integer", false), "1", null);
        var checked = previewService.preview(moduleAlias, relationId, proposal);
        applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(proposal, checked.proposalFingerprint()));
        var saved = field("quantity");
        var inherited = new MetadataFieldConfig();
        inherited.setMetadataFieldId(saved.getId());
        inherited.setDefaultValue("2");
        fieldConfigs.insert(inherited);
        assertThatThrownBy(() -> deletion.deleteField(moduleAlias, relationId, saved.getId()))
                .isInstanceOf(PlatformException.class).hasMessageContaining("字段配置");
        assertThat(fieldService.select(saved.getId())).isNotNull();
        assertThat(fieldConfigs.findRelationOverride(saved.getId(), relationId).getDefaultValue()).isEqualTo("1");
        assertThat(fieldConfigs.findByMetadataFieldId(saved.getId()).getDefaultValue()).isEqualTo("2");
        assertThat(columnExists(metadata.getTableName(), "quantity")).isTrue();
    }

    @Test
    void fixedDefaultAndPhysicalFieldRollBackTogetherWhenSchemaPublicationFails() {
        var proposal = withFixedDefault(proposal("quantity", "quantity", "integer", false), "1", null);
        var checked = previewService.preview(moduleAlias, relationId, proposal);
        var initialConfigCount = fieldConfigs.count(Criteria.of());
        schemaEnsureService.failAfterEnsure = true;
        assertThatThrownBy(() -> applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(proposal, checked.proposalFingerprint())))
                .hasMessageContaining("forced schema failure");
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId()).eq("fieldName", "quantity"))).isEmpty();
        assertThat(fieldConfigs.count(Criteria.of())).isEqualTo(initialConfigCount);
        assertThat(columnExists(metadata.getTableName(), "quantity")).isFalse();
        schemaEnsureService.failAfterEnsure = false;
        applyService.apply(moduleAlias, relationId, new MetadataRelationChangeSetApplyCommand(proposal, checked.proposalFingerprint()));
        var saved = fieldService.list(Criteria.of().eq("metadataId", metadata.getId()).eq("fieldName", "quantity")).getFirst();
        assertThat(fieldConfigs.findRelationOverride(saved.getId(), relationId).getDefaultValue()).isEqualTo("1");
    }

    private MetadataRelationChangeSetPreviewCommand withFixedDefault(MetadataRelationChangeSetPreviewCommand original,
                                                                    String value, Integer configVersion) {
        var field = original.fieldDrafts().getFirst();
        return new MetadataRelationChangeSetPreviewCommand(original.expectedMetadataVersion(), original.capabilitySelections(),
                List.of(new MetadataFieldChangeSetDraft(field.operation(), field.fieldId(), field.expectedFieldVersion(), field.field(),
                        new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.BASIC, null, null, null,
                                new MetadataFieldFixedDefaultDraft(value, configVersion)))));
    }

    @Test
    void childCreationReceiptRollsBackWithSchemaAndCanRetryTheSameRequest() {
        installChildCreationTransactions();
        var command = childCreationCommand();
        var owner = CurrentUser.systemUser("receipt-owner", "owner");
        asConfigurationUser(owner, () -> {
            schemaEnsureService.failAfterEnsure = true;
            assertThatThrownBy(() -> childCreation.create(moduleAlias, relationId, command))
                    .hasMessageContaining("forced schema failure");
            assertNoChildCreation(command);
            schemaEnsureService.failAfterEnsure = false;
            new TransactionTemplate(transactionManager).execute(status -> {
                childCreation.create(moduleAlias, relationId, command);
                assertThat(childCreation.lookup(moduleAlias, relationId, command.requestId())).isNotNull();
                status.setRollbackOnly();
                return null;
            });
            assertNoChildCreation(command);
            var committed = childCreation.create(moduleAlias, relationId, command);
            assertThat(childCreation.lookup(moduleAlias, relationId, command.requestId()))
                    .isEqualTo(new ModuleChildMetadataCreationService.Receipt(committed.metadata().getId(), committed.relation().getId()));
            assertThat(columnExists(command.tableName(), "id")).isTrue();
            assertThat(childReceipts.list(Criteria.of().eq("moduleAlias", moduleAlias))).hasSize(1);
            return null;
        });
    }

    @Test
    void concurrentChildCreationRetriesCommitOneChildAndOneReceipt() throws Exception {
        installChildCreationTransactions();
        var command = childCreationCommand();
        var owner = CurrentUser.systemUser("receipt-owner", "owner");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("concurrent start timed out");
                return asConfigurationUser(owner, () -> childCreation.create(moduleAlias, relationId, command));
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var first = futures.getFirst().get(30, TimeUnit.SECONDS);
            var second = futures.getLast().get(30, TimeUnit.SECONDS);
            assertThat(second.metadata().getId()).isEqualTo(first.metadata().getId());
            assertThat(second.relation().getId()).isEqualTo(first.relation().getId());
            assertThat(metadataService.list(Criteria.of().eq("alias", command.alias()))).hasSize(1);
            assertThat(relationService.list(Criteria.of().eq("moduleAlias", moduleAlias).eq("relationRole", RelationRole.CHILD)))
                    .singleElement().extracting(ModuleMetadataRelation::getMetadataId).isEqualTo(first.metadata().getId());
            assertThat(childReceipts.list(Criteria.of().eq("moduleAlias", moduleAlias)))
                    .singleElement().extracting(ModuleChildMetadataCreationReceipt::getMetadataId).isEqualTo(first.metadata().getId());
            assertThat(columnExists(command.tableName(), "id")).isTrue();
        }
    }

    @Test
    void childCreationReceiptRejectsChangedPayloadAndRemainsScopedAndAuthorized() {
        installChildCreationTransactions();
        var command = childCreationCommand();
        var owner = CurrentUser.tenantUser("receipt-owner", "owner", "receipt-tenant");
        var result = asConfigurationUser(owner, () -> childCreation.create(moduleAlias, relationId, command));
        asConfigurationUser(owner, () -> {
            var altered = new ModuleChildMetadataCreateCommand(command.alias(), "篡改标题", command.schemaName(),
                    command.tableName(), command.requestId());
            assertThatThrownBy(() -> childCreation.create(moduleAlias, relationId, altered))
                    .hasMessageContaining("请求内容已变化");
            assertThat(childCreation.lookup(moduleAlias, relationId, command.requestId()).metadataId())
                    .isEqualTo(result.metadata().getId());
            return null;
        });
        assertThat(asConfigurationUser(CurrentUser.tenantUser("other-owner", "other", "receipt-tenant"),
                () -> childCreation.lookup(moduleAlias, relationId, command.requestId()))).isNull();
        assertThat(asConfigurationUser(CurrentUser.tenantUser("receipt-owner", "owner", "other-tenant"),
                () -> childCreation.lookup(moduleAlias, relationId, command.requestId()))).isNull();
        Mockito.doThrow(new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("creation permission revoked"))
                .when(childCreationPermissions).requireAuthorized(any());
        asConfigurationUser(owner, () -> {
            assertThatThrownBy(() -> childCreation.lookup(moduleAlias, relationId, command.requestId()))
                    .hasMessageContaining("permission revoked");
            assertThatThrownBy(() -> childCreation.create(moduleAlias, relationId, command))
                    .hasMessageContaining("permission revoked");
            return null;
        });
        assertThat(childReceipts.list(Criteria.of().eq("moduleAlias", moduleAlias))).hasSize(1);
    }

    private ModuleChildMetadataCreateCommand childCreationCommand() {
        String key = UUID.randomUUID().toString().replace("-", "");
        return new ModuleChildMetadataCreateCommand("child_" + key, "明细", "public", "app_child_" + key,
                UUID.randomUUID().toString());
    }

    private void assertNoChildCreation(ModuleChildMetadataCreateCommand command) {
        assertThat(childCreation.lookup(moduleAlias, relationId, command.requestId())).isNull();
        assertThat(childReceipts.list(Criteria.of().eq("moduleAlias", moduleAlias))).isEmpty();
        assertThat(metadataService.list(Criteria.of().eq("alias", command.alias()))).isEmpty();
        assertThat(relationService.list(Criteria.of().eq("moduleAlias", moduleAlias).eq("relationRole", RelationRole.CHILD))).isEmpty();
        assertThat(columnExists(command.tableName(), "id")).isFalse();
    }

    private <T> T asConfigurationUser(CurrentUser user, Supplier<T> operation) {
        try (var identity = CurrentUserContext.use(user); var scope = TenantContext.system("child creation receipt contract")) {
            return operation.get();
        }
    }

    private void installChildCreationTransactions() {
        var jdbc = new JdbcTemplate(dataSource);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            @Override public <T> T execute(Supplier<T> work) {
                return new TransactionTemplate(transactionManager).execute(status -> work.get());
            }
            @Override public void lock(String scope, String key) {
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope + ":" + key);
            }
        });
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
        assertThat(compositionCommitted(id, command)).isFalse();
        if (invalidPage) {
            assertThatThrownBy(() -> saveComposition(id, command)).hasMessageContaining("not registered");
            assertThat(metadataService.list(Criteria.of().eq("alias", alias))).isEmpty();
            assertThat(columnExists(alias, "id")).isFalse();
            assertThat(revisionService.select(id).getStatus()).isEqualTo(PlatformPresentationRevisionStatus.DRAFT);
            assertThat(compositionCommitted(id, command)).isFalse();
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
            assertThat(compositionCommitted(id, command)).isTrue();
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
        assertThat(compositionCommitted(revision.getId(), command)).isFalse();
        String originalTree = command.revision().getUiTreeJson();
        saveComposition(revision.getId(), command);
        assertThat(compositionCommitted(revision.getId(), command)).isTrue();
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("different-publisher", "另一发布人"))) {
            assertThat(compositionCommitted(revision.getId(), command)).isFalse();
        }
        assertThat(revisionService.select(revision.getId()).getUiTreeJson()).isNotEqualTo(originalTree);
        var otherInput = new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand(command.revision(),
                command.relationId(), command.expectedMetadataVersion(),
                List.of(new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand.NewField(key, "其他电话", "text", true, "lianXiDianHua")));
        assertThat(compositionCommitted(revision.getId(), otherInput)).isFalse();
        assertThat(compositionCommitted(revision.getId(), new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand(
                command.revision(), command.relationId(), command.expectedMetadataVersion() + 1, command.newFields()))).isFalse();
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
    void shouldRollBackCompositionReceiptWithItsFieldAndPage() {
        String key = UUID.randomUUID().toString().replace("-", "");
        var revision = pageRevision("""
                {"template":"management","templateVersion":1,"nodes":[
                {"slot":"list","title":"列表","fields":[]},
                {"slot":"form","title":"表单","fields":["field%s"]}]}
                """.formatted(key));
        var command = new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand(
                revisionService.select(revision.getId()), relationId, metadataService.select(metadata.getId()).getVersion(),
                List.of(new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand.NewField(key, "名称", "text", false, "rolledBackName")));
        new TransactionTemplate(transactionManager).execute(status -> {
            saveComposition(revision.getId(), command);
            assertThat(compositionCommitted(revision.getId(), command)).isTrue();
            status.setRollbackOnly();
            return null;
        });
        assertThat(compositionCommitted(revision.getId(), command)).isFalse();
        assertThat(revisionService.select(revision.getId()).getStatus()).isEqualTo(PlatformPresentationRevisionStatus.DRAFT);
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId()).eq("fieldName", "rolledBackName"))).isEmpty();
        assertThat(columnExists(metadata.getTableName(), "rolled_back_name")).isFalse();
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
    void shouldRejectBusinessEnableFieldBeforeAnyConfigurationOrDdlWrite() {
        var candidate = proposal("enabled", "enabled", fieldSpecAlias(), false);
        var preview = previewService.preview(moduleAlias, relationId, candidate);
        assertThat(preview.errors()).extracting(MetadataChangeSetValidationIssue::code)
                .contains("CAPABILITY_FIELD_CONFLICT");
        assertThatThrownBy(() -> applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(candidate, preview.proposalFingerprint())))
                .isInstanceOf(RuntimeException.class);
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId())))
                .extracting(MetadataField::getFieldName).doesNotContain("enabled");
        assertThat(columnExists(metadata.getTableName(), "enabled")).isFalse();
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
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId())).stream()
                .filter(field -> "enabled".equals(field.getFieldName())).findFirst().orElseThrow())
                .satisfies(field -> {
                    assertThat(field.getFieldOwnership()).isEqualTo(MetadataFieldOwnership.STANDARD);
                    assertThat(field.getSystemManaged()).isTrue();
                });
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
    void mainApprovalIntentPublishesManagedSummaryFieldsAndRealRuntimeCapability() {
        String alias = "crm.approval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var module = new PlatformModule(); module.setAlias(alias); module.setApplicationAlias("crm");
        module.setTitle("审批业务");
        module.setModuleKind(ModuleKind.DYNAMIC); module.setMainCapabilityDeclarations(Set.of("APPROVAL"));
        when(moduleService.select(alias)).thenReturn(module);
        var created = orchestration.createMainMetadata(alias, new ModuleMainMetadataCreateCommand(
                alias.substring(4), "审批业务", "public", "app_" + alias.substring(4), false));
        assertThat(created.metadata().getCapabilityDeclarations()).containsExactly("APPROVAL");
        assertApprovalPublication(alias, created.metadata());
        var snapshot = new ModuleMetadataCapabilitySnapshotService(relationService, metadataService, fieldService)
                .snapshot(alias, created.relation().getId());
        assertThat(snapshot.capabilities()).filteredOn(fact -> fact.capability() == EntityCapability.APPROVAL)
                .singleElement().satisfies(fact -> {
                    assertThat(fact.enabled()).isTrue(); assertThat(fact.changeSetConfigurable()).isTrue();
                    assertThat(fact.defaultKind()).isEqualTo("CONTEXT"); assertThat(fact.fieldContributions()).hasSize(5);
                });
        verify(refreshCoordinator).scheduleModules(List.of(alias));
    }

    @Test
    void approvalChangeSetPublishesOnAnExistingModelWithoutLosingBusinessRecordsAndCannotBeDisabled() {
        applyNewStringField("title", "title");
        new JdbcTemplate(dataSource).update("INSERT INTO public." + metadata.getTableName()
                + " (id, version, title) VALUES (?, ?, ?)", "preserved-business", 1, "保留业务");
        Mockito.clearInvocations(refreshCoordinator);
        var current = metadataService.select(metadata.getId());
        var proposal = new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(
                relationId, current.getVersion(), Map.of(EntityCapability.APPROVAL, true), List.of())), List.of(), List.of());
        var preview = modelPreview.preview(moduleAlias, proposal);
        assertThat(preview.errors()).isEmpty();
        assertThat(preview.fieldImpacts()).filteredOn(MetadataChangeSetFieldImpact::platformManaged).hasSize(5);
        modelApply.apply(moduleAlias, new MetadataModelChangeSetApplyCommand(proposal, preview.proposalFingerprint()));
        var published = metadataService.select(metadata.getId());
        assertThat(published.getCapabilityDeclarations()).contains("APPROVAL");
        assertApprovalPublication(moduleAlias, published);
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT title FROM public." + metadata.getTableName()
                + " WHERE id = ?", String.class, "preserved-business")).isEqualTo("保留业务");
        var disabled = previewService.preview(moduleAlias, relationId, new MetadataRelationChangeSetPreviewCommand(
                published.getVersion(), Map.of(EntityCapability.APPROVAL, false), List.of()));
        assertThat(disabled.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("NON_ADDITIVE_CAPABILITY");
        verify(refreshCoordinator).scheduleModules(List.of(moduleAlias));
    }

    @Test
    void approvalPublicationFailureRollsBackDeclarationsMetadataFieldsAndPhysicalColumns() {
        var proposal = new MetadataRelationChangeSetPreviewCommand(metadata.getVersion(), Map.of(EntityCapability.APPROVAL, true), List.of());
        var preview = previewService.preview(moduleAlias, relationId, proposal);
        assertThat(preview.errors()).isEmpty(); schemaEnsureService.failAfterEnsure = true;
        assertThatThrownBy(() -> applyService.apply(moduleAlias, relationId,
                new MetadataRelationChangeSetApplyCommand(proposal, preview.proposalFingerprint())))
                .hasMessageContaining("forced schema failure");
        assertThat(metadataService.select(metadata.getId()).getCapabilityDeclarations()).isNull();
        for (var summary : net.ximatai.muyun.spring.dynamic.metadata.DynamicAbilityFields.approvalFields()) {
            assertThat(columnExists(metadata.getTableName(), summary.columnName())).isFalse();
            assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId()).eq("fieldName", summary.fieldName()))).isEmpty();
        }
        Mockito.verifyNoInteractions(refreshCoordinator);
    }

    @Test
    void approvalSummaryFieldNamesAndColumnsCannotBeCreatedAsOrdinaryBusinessFields() {
        for (var summary : net.ximatai.muyun.spring.dynamic.metadata.DynamicAbilityFields.approvalFields()) {
            var proposal = proposal(summary.fieldName(), summary.columnName(), summary.type() == FieldType.TIMESTAMP ? "datetime" : "string", false);
            assertThat(previewService.preview(moduleAlias, relationId, proposal).errors())
                    .extracting(MetadataChangeSetValidationIssue::code).contains("CAPABILITY_FIELD_CONFLICT");
        }
        assertThat(fieldService.list(Criteria.of().eq("metadataId", metadata.getId()))).isEmpty();
        Mockito.verifyNoInteractions(refreshCoordinator);
    }

    @Test
    void existingAggregateMainCanEnableApprovalWithoutGrantingApprovalToItsChild() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var child = orchestration.createChildMetadata(moduleAlias, relationId, new ModuleChildMetadataCreateCommand(
                "line_" + suffix, "业务明细", "public", "app_line_" + suffix));
        var snapshot = new ModuleMetadataCapabilitySnapshotService(relationService, metadataService, fieldService);
        assertThat(snapshot.snapshot(moduleAlias, relationId).capabilities())
                .filteredOn(fact -> fact.capability() == EntityCapability.APPROVAL).singleElement()
                .satisfies(fact -> assertThat(fact.changeSetConfigurable()).isTrue());
        var current = metadataService.select(metadata.getId());
        var proposal = new MetadataRelationChangeSetPreviewCommand(current.getVersion(), Map.of(EntityCapability.APPROVAL, true), List.of());
        var preview = previewService.preview(moduleAlias, relationId, proposal);
        assertThat(preview.errors()).isEmpty();
        applyService.apply(moduleAlias, relationId, new MetadataRelationChangeSetApplyCommand(proposal, preview.proposalFingerprint()));
        assertApprovalPublication(moduleAlias, metadataService.select(metadata.getId()));
        assertThat(entityCompiler.compile(child.metadata().getId()).capabilities()).doesNotContain(EntityCapability.APPROVAL);
        assertThat(columnExists(child.metadata().getTableName(), "approval_instance_id")).isFalse();
        var foreignKey = fieldService.list(Criteria.of().eq("metadataId", child.metadata().getId())
                .eq("fieldName", child.relation().getForeignKey())).getFirst();
        assertThat(columnExists(child.metadata().getTableName(), foreignKey.getColumnName())).isTrue();
        var rejected = previewService.preview(moduleAlias, child.relation().getId(), new MetadataRelationChangeSetPreviewCommand(
                child.metadata().getVersion(), Map.of(EntityCapability.APPROVAL, true), List.of()));
        assertThat(rejected.errors()).extracting(MetadataChangeSetValidationIssue::code).contains("CHILD_CAPABILITY_UNSUPPORTED");
    }

    private void assertApprovalPublication(String alias, Metadata published) {
        var summaryFields = net.ximatai.muyun.spring.dynamic.metadata.DynamicAbilityFields.approvalFields();
        var fields = fieldService.list(Criteria.of().eq("metadataId", published.getId()));
        for (var expected : summaryFields) {
            assertThat(fields).filteredOn(field -> field.getFieldName().equals(expected.fieldName())).singleElement()
                    .satisfies(field -> {
                        assertThat(field.getSystemManaged()).isTrue(); assertThat(field.getFieldOwnership()).isEqualTo(MetadataFieldOwnership.STANDARD);
                        assertThat(fieldCompiler.compile(field)).isEqualTo(expected);
                    });
            assertThat(columnExists(published.getTableName(), expected.columnName())).isTrue();
            if (expected.type() == FieldType.STRING) assertThat(columnLength(published.getTableName(), expected.columnName())).isEqualTo(expected.length());
            else assertThat(columnDataType(published.getTableName(), expected.columnName())).startsWith("timestamp");
        }
        var compiler = new net.ximatai.muyun.spring.platform.runtime.PlatformModuleDefinitionCompiler(moduleService,
                metadataService, fieldService, fieldCompiler, referenceConfigs, relationService,
                mock(MetadataViewService.class), mock(MetadataViewFieldService.class),
                mock(net.ximatai.muyun.spring.platform.module.PlatformModuleActionService.class), mock(ModuleMetadataFormulaRuleService.class));
        var definition = compiler.compile(alias);
        assertThat(definition.entities().getFirst().capabilities()).contains(EntityCapability.APPROVAL, EntityCapability.WORKFLOW);
        assertThat(definition.actions()).extracting(net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition::actionCode).contains("submitApproval");
        try (var runtime = net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime.builder(operations).build()) {
            runtime.register(definition);
            assertThat(runtime.describe(alias).entities().getFirst().capabilities()).contains("APPROVAL", "WORKFLOW");
            assertThat(runtime.entityService(alias, published.getAlias()).supportsApproval()).isTrue();
            var records = new DynamicRecordService(runtime);
            var record = records.newRecord(alias, published.getAlias());
            if (definition.entities().getFirst().fields().stream().anyMatch(field -> field.fieldName().equals("title"))) {
                record.setValue("title", "审批发布运行态契约");
            }
            String id = records.create(alias, published.getAlias(), record);
            var submittedAt = java.time.Instant.parse("2026-10-07T01:02:03Z");
            var completedAt = submittedAt.plusSeconds(60);
            new JdbcTemplate(dataSource).update("UPDATE public." + published.getTableName()
                            + " SET approval_instance_id = ?, approval_status = ?, approval_submitted_by = ?,"
                            + " approval_submitted_at = ?, approval_completed_at = ? WHERE id = ?",
                    "published-instance", "APPROVED", "published-user", java.sql.Timestamp.from(submittedAt),
                    java.sql.Timestamp.from(completedAt), id);
            var loaded = records.select(alias, published.getAlias(), id);
            assertThat(loaded.getApprovalInstanceId()).isEqualTo("published-instance");
            assertThat(loaded.getApprovalStatus()).isEqualTo("APPROVED");
            assertThat(loaded.getApprovalSubmittedBy()).isEqualTo("published-user");
            assertThat(loaded.getApprovalSubmittedAt()).isEqualTo(submittedAt);
            assertThat(loaded.getApprovalCompletedAt()).isEqualTo(completedAt);
            assertThat(records.list(alias, published.getAlias(), Criteria.of().eq("id", id),
                    net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10)))
                    .singleElement().satisfies(row -> assertThat(row.getApprovalInstanceId()).isEqualTo("published-instance"));
            assertThatThrownBy(() -> records.newRecord(alias, published.getAlias()).setValue("approvalStatus", "APPROVED"))
                    .hasMessageContaining("platform managed");
        }
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

    private boolean compositionCommitted(String id, net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand command) {
        try (var ignored = TenantContext.system("test page composition receipt")) {
            return compositionSave.committed(id, command);
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
        @Bean ActionExecutionPolicyService childCreationPermissions() { return mock(ActionExecutionPolicyService.class); }
        @Bean ModuleChildMetadataCreationService childCreation(ModuleChildMetadataCreationReceiptDao receipts,
                ModuleMetadataOrchestrationService orchestration, ModuleMetadataRelationService relations,
                MetadataService metadata, ActionExecutionPolicyService permissions) {
            return new ModuleChildMetadataCreationService(receipts, orchestration, relations, metadata, permissions);
        }
        @Bean MetadataModelDeletionService deletion(ModuleMetadataRelationService relations, MetadataService metadata,
                MetadataFieldService fields, PlatformMetadataEntityDefinitionCompiler compiler, TestSchemaEnsureService schema,
                DynamicRecordService records, PlatformDynamicRuntimeRefreshCoordinator refresh, MetadataFieldConfigService configs,
                MetadataFieldReferenceConfigService references) {
            return new MetadataModelDeletionService(relations, metadata, fields, mock(ModuleMetadataFieldService.class), configs, references,
                    compiler, schema, records, refresh);
        }
        @Bean MetadataFieldReferenceConfigService referenceConfigs(MetadataFieldReferenceConfigDao dao,
                MetadataFieldService fields, MetadataService metadata, FieldSpecService specs,
                PlatformModuleService modules, ModuleMetadataRelationService relations) {
            return new MetadataFieldReferenceConfigService(dao, fields, metadata, specs, modules, relations, Optional.empty());
        }
        @Bean ConfigurationReferenceContributor fieldReferenceReference(org.springframework.beans.factory.ObjectProvider<MetadataFieldReferenceConfigService> configs) {
            return new ConfigurationReferenceContributorConfiguration().fieldReferenceReference(configs);
        }
        @Bean ConfigurationReferenceContributor fieldConfigReference(org.springframework.beans.factory.ObjectProvider<MetadataFieldConfigService> configs) {
            return new ConfigurationReferenceContributorConfiguration().fieldConfigReference(configs);
        }
        @Bean FieldSpecService fieldSpecService(FieldSpecDao dao) { return new FieldSpecService(dao, mock(BaseDao.class)); }
        @Bean net.ximatai.muyun.spring.platform.ui.PageCompositionSaveService compositionSave(
                PlatformPresentationRevisionService revisions, PlatformPresentationVariantService variants,
                PlatformPageDefinitionService pages, MetadataRelationChangeSetPreviewService preview,
                MetadataRelationChangeSetApplyService apply, ModuleMetadataRelationService relations, MetadataService metadata, ModuleMetadataOrchestrationService orchestration, MetadataFieldService fields,
                net.ximatai.muyun.spring.platform.ui.PageCompositionSaveReceiptDao receipts) {
            return new net.ximatai.muyun.spring.platform.ui.PageCompositionSaveService(revisions, variants, pages, preview, apply,
                    new net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionPublishService(revisions, variants, pages,
                            new net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplateCatalog(),
                            TestBeanProviders.empty(net.ximatai.muyun.spring.platform.ui.PublishedPageExecutionCoordinator.class),
                            net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher.noop()), relations, metadata, orchestration, fields, receipts);
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
                org.springframework.beans.factory.ObjectProvider<ConfigurationReferenceDeletionGuard> guard,
                org.springframework.beans.factory.ObjectProvider<MetadataFieldConfigService> configs) {
            var empty = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            return new MetadataFieldService(
                    dao,
                    metadata,
                    specs,
                    empty.getBeanProvider(PlatformDynamicRuntimeRefreshCoordinator.class),
                    empty.getBeanProvider(PlatformMetadataSchemaEnsureService.class),
                    guard,
                    empty.getBeanProvider(ModuleMetadataRelationService.class),
                    empty.getBeanProvider(PlatformModuleService.class),
                TestBeanProviders.empty(MetadataFieldReferenceConfigService.class),
                configs);
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
        @Bean ModuleMetadataFieldService moduleFieldService(ModuleMetadataFieldDao dao,
                ModuleMetadataRelationService relations, MetadataService metadata, MetadataFieldService fields,
                FieldSpecService specs) {
            return new ModuleMetadataFieldService(dao, relations, metadata, fields, specs, Optional.empty(),
                    Optional.empty(), TestBeanProviders.empty(ConfigurationReferenceDeletionGuard.class));
        }
        @Bean MetadataFieldProtectionConfigService protectionConfigService(MetadataFieldService fields, FieldSpecService specs) {
            return new MetadataFieldProtectionConfigService(new TestMemoryDao<>(), fields, specs, new TestMemoryDao<>(), Optional.empty());
        }
        @Bean DictionaryItemService dictionaryItemService() { return mock(DictionaryItemService.class); }
        @Bean MetadataFieldConfigService metadataFieldConfigService(MetadataFieldConfigDao dao, MetadataFieldService fields,
                MetadataService metadata, FieldSpecService specs, ModuleMetadataRelationService relations,
                MetadataFieldProtectionConfigService protection, DictionaryItemService dictionaryItems,
                org.springframework.beans.factory.ObjectProvider<ModuleMetadataFieldService> moduleFields) {
            return new MetadataFieldConfigService(dao, fields, metadata, specs,
                    mock(net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService.class), new DictionaryFieldValueValidator(dictionaryItems), relations, protection, Optional.empty(),
                moduleFields);
        }
        @Bean MetadataFieldDefinitionCompiler fieldCompiler(FieldSpecService specs, MetadataFieldConfigService configs, MetadataFieldService fields, MetadataFieldProtectionConfigService protection) {
            return new MetadataFieldDefinitionCompiler(specs, configs,
                    protection, fields);
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
                                                                      FieldSpecService specs, DynamicRecordService records, MetadataFieldConfigService configs, ModuleMetadataFieldService moduleFields) {
            return new MetadataRelationChangeSetPreviewService(modules, relations, metadata, fields, specs,
                    null, configs, moduleFields, records);
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
                                                                  ModuleMetadataCapabilitySnapshotService snapshots, MetadataFieldConfigService configs,
                                                                  PlatformMetadataEntityDefinitionCompiler compiler, DynamicRecordService records) {
            return new MetadataRelationChangeSetApplyService(preview, relations, metadata, fields, schema, refresh, snapshots, null, configs, compiler, records);
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
