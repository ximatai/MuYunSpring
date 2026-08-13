package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrityPolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewDisplayMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityAssociationViewDefinition;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityFormulaRuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewFieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;
import net.ximatai.muyun.spring.common.model.file.FileReferenceStoragePolicy;
import net.ximatai.muyun.spring.dynamic.metadata.FieldTemporalSemantics;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicModuleDescriptorTest {
    @Test
    void shouldExposeDatabaseInlineStoragePolicyToTheSharedFormRuntime() {
        EntityDefinition document = new EntityDefinition("document", "crm_document", "Document", List.of(
                FieldDefinition.string("logoAssetId", "Logo").column("logo_asset_id")))
                .withFileReferences(Map.of("logoAssetId", new FileReferenceDefinition(Set.of("image/png"), 1024L, 1,
                        Map.of(), FileReferenceStoragePolicy.DATABASE_INLINE)));

        DynamicEntityDescriptor entity = DynamicModuleDescriptor.from(new ModuleDefinition(
                "crm.document", "Document", List.of(document))).entities().getFirst();

        assertThat(entity.fileReferences().getFirst().storagePolicy())
                .isEqualTo(FileReferenceStoragePolicy.DATABASE_INLINE);
    }

    @Test
    void shouldExposeDeclaredSingleFileReferencesInTheRuntimeDescriptor() {
        EntityDefinition document = new EntityDefinition("document", "crm_document", "Document", List.of(
                FieldDefinition.string("sourceFileId", "Source File").column("source_file_id").length(64)))
                .withFileReferences(Map.of("sourceFileId",
                        new FileReferenceDefinition(Set.of("application/pdf"), 1024L)));

        DynamicEntityDescriptor entity = DynamicModuleDescriptor.from(new ModuleDefinition(
                "crm.document", "Document", List.of(document))).entities().getFirst();

        assertThat(entity.fileReferences()).containsExactly(new DynamicFileReferenceDescriptor(
                "sourceFileId", Set.of("application/pdf"), 1024L));
    }

    @Test
    void shouldExposeZonedTimestampTimeZoneCompanionInDescriptors() {
        ModuleDefinition module = new ModuleDefinition(
                "sales.meeting",
                "Meeting",
                List.of(new EntityDefinition("meeting", "app_meeting", "Meeting", List.of(
                        FieldDefinition.zonedTimestamp("meetingAt", "Meeting At").column("meeting_at"),
                        FieldDefinition.zonedTimestampTimeZone("meetingAt", "meeting_at")
                )))
        );

        DynamicEntityDescriptor entity = DynamicModuleDescriptor.from(module).entities().getFirst();

        assertThat(entity.fields().getFirst().fieldName()).isEqualTo("meetingAt");
        assertThat(entity.fields().getFirst().temporalSemantics()).isEqualTo(FieldTemporalSemantics.ZONED_INSTANT);
        assertThat(entity.fields().getFirst().companions())
                .containsExactly(new DynamicFieldCompanionDescriptor(
                        "meetingAtTimeZone",
                        "ZONED_TIMESTAMP",
                        "TIME_ZONE",
                        true,
                        true
                ));
        assertThat(entity.fields().get(1).companions()).isEmpty();
        assertThat(entity.views().getFirst().fields().getFirst().temporalSemantics())
                .isEqualTo(FieldTemporalSemantics.ZONED_INSTANT);
        assertThat(entity.views().getFirst().fields().getFirst().companions())
                .containsExactly(new DynamicFieldCompanionDescriptor(
                        "meetingAtTimeZone",
                        "ZONED_TIMESTAMP",
                        "TIME_ZONE",
                        true,
                        true
                ));
    }

    @Test
    void shouldExposeRuntimeModuleDefinitionAsStableDescriptor() {
        ModuleDefinition module = ModuleDefinition.builder("crm.customer", "Customer")
                .entities(List.of(
                        new EntityDefinition("customer", "crm_customer", "Customer", List.of(
                                FieldDefinition.titleField().queryable(),
                        FieldDefinition.string("status", "Status")
                                        .dictionary("crm", "customer_status")
                                        .defaultUiType("select")
                                        .defaultValue("active")
                                        .validationRegex("[a-z_]+")
                                        .notCopyable(),
                                FieldDefinition.of("tags", FieldType.JSON, "Tags")
                                        .dictionary("crm", "customer_tag", OptionSelectionMode.MULTIPLE)
                        ), Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE))
                                .withFormulaRules(
                                        EntityFormulaRuleDefinition
                                                .calculation("lateRule", "title", "{title}")
                                                .sortOrder(20),
                                        EntityFormulaRuleDefinition
                                                .calculation("statusTitle", "title", "{status} + '-' + {title}")
                                                .phase(FormulaRulePhase.DEFAULT_VALUE)
                                                .sortOrder(10)
                                ),
                        new EntityDefinition("contact", "crm_contact", "Contact", List.of(
                                FieldDefinition.titleField(),
                                FieldDefinition.string("customerId", "Customer")
                        ), Set.of(EntityCapability.CRUD))
                ))
                .relations(List.of(EntityRelationDefinition.child("contacts", "customer", "contact", "customerId")
                        .withAutoPopulate()))
                .references(List.of(EntityReferenceDefinition.to("contact", "customerId", "crm.customer.customer")
                        .withProjection("title", "customerTitle")
                        .withProjection("title", "customerTitle")
                        .withIntegrity(new ReferenceIntegrityPolicy(
                                ReferenceTargetUnavailablePolicy.RESTRICT))))
                .views(List.of())
                .associationViews(List.of(
                        EntityAssociationViewDefinition.childRelation("contacts", "customer", "crm.customer",
                                "contact", "contacts"),
                        EntityAssociationViewDefinition.reference("customerId", "contact", "crm.customer",
                                "customer", "customerId")
                ))
                .actions(List.of())
                .build();

        DynamicModuleDescriptor descriptor = DynamicModuleDescriptor.from(module);

        assertThat(descriptor.moduleAlias()).isEqualTo("crm.customer");
        assertThat(descriptor.mainEntityAlias()).isEqualTo("customer");
        assertThat(descriptor.actions())
                .extracting(DynamicActionDescriptor::code)
                .contains("create", "view", "update", "delete", "query", "reference");
        assertThat(descriptor.entities()).extracting(DynamicEntityDescriptor::entityAlias)
                .containsExactly("customer", "contact");
        assertThat(descriptor.entities().getFirst().capabilities()).contains("CRUD", "REFERENCE");
        assertThat(descriptor.entities().getFirst().formulaRules().getFirst())
                .satisfies(rule -> {
                    assertThat(rule.code()).isEqualTo("statusTitle");
                    assertThat(rule.kind()).isEqualTo(FormulaRuleKind.CALCULATION);
                    assertThat(rule.phase()).isEqualTo(FormulaRulePhase.DEFAULT_VALUE);
                    assertThat(rule.targetField()).isEqualTo("title");
                    assertThat(rule.sortOrder()).isEqualTo(10);
                });
        assertThat(descriptor.entities().getFirst().actions())
                .extracting(DynamicActionDescriptor::code)
                .contains("create", "view", "update", "delete", "query", "reference");
        assertThat(descriptor.entities().getFirst().actions().stream()
                .filter(action -> action.code().equals("create"))
                .findFirst())
                .get()
                .satisfies(action -> {
                    assertThat(action.actionAuth()).isTrue();
                    assertThat(action.accessMode()).isNotNull();
                });
        DynamicFieldDescriptor status = descriptor.entities().getFirst().fields().get(1);
        assertThat(status.fieldName()).isEqualTo("status");
        assertThat(status.optionBinding()).isEqualTo(OptionBinding.dictionary("crm", "customer_status"));
        assertThat(status.selectionMode()).isEqualTo(OptionSelectionMode.SINGLE);
        assertThat(status.defaultValue()).isEqualTo("active");
        assertThat(status.validationRegex()).isEqualTo("[a-z_]+");
        assertThat(status.copyable()).isFalse();
        assertThat(status.writeProtected()).isFalse();
        DynamicViewDescriptor listView = descriptor.entities().getFirst().views().getFirst();
        assertThat(listView.viewType()).isEqualTo(EntityViewType.LIST);
        assertThat(listView.fields())
                .extracting(DynamicViewFieldDescriptor::fieldName)
                .containsExactly("title", "status", "tags");
        assertThat(listView.fields().get(1).controlType()).isEqualTo(ViewControlType.SELECT);
        assertThat(listView.fields().get(1).fieldUiControlAlias()).isEqualTo("select");
        assertThat(listView.fields().get(2).controlType()).isEqualTo(ViewControlType.MULTI_SELECT);
        assertThat(descriptor.entities().getFirst().fields().getFirst().query().defaultOperator())
                .isEqualTo(DynamicQueryOperator.LIKE.name());
        assertThat(descriptor.entities().getFirst().fields().getFirst().query().operators())
                .containsExactly("EQ", "NOT_EQUAL", "LIKE", "IN", "NOT_IN", "NULL", "NOT_NULL");
        assertThat(descriptor.relations().getFirst().code()).isEqualTo("contacts");
        assertThat(descriptor.relations().getFirst().autoPopulate()).isTrue();
        assertThat(descriptor.relations().getFirst().cascadeOnParentUnavailable()).isFalse();
        assertThat(descriptor.associationViews())
                .extracting(DynamicAssociationViewDescriptor::code)
                .containsExactly("contacts", "customerId");
        assertThat(descriptor.entities().getFirst().associationViews().getFirst())
                .satisfies(view -> {
                    assertThat(view.displayMode()).isEqualTo(AssociationViewDisplayMode.INLINE_LIST);
                    assertThat(view.targetEntityAlias()).isEqualTo("contact");
                    assertThat(view.relationCode()).isEqualTo("contacts");
                    assertThat(view.queryable()).isTrue();
                });
        assertThat(descriptor.entities().get(1).associationViews().getFirst())
                .satisfies(view -> {
                    assertThat(view.displayMode()).isEqualTo(AssociationViewDisplayMode.LINKED_RECORD);
                    assertThat(view.targetEntityAlias()).isEqualTo("customer");
                    assertThat(view.referenceField()).isEqualTo("customerId");
                    assertThat(view.viewType()).isEqualTo(EntityViewType.FORM);
                    assertThat(view.queryable()).isTrue();
                });
        DynamicReferenceDescriptor reference = descriptor.references().getFirst();
        assertThat(reference.sourceEntityAlias()).isEqualTo("contact");
        assertThat(reference.targetModuleAlias()).isEqualTo("crm.customer");
        assertThat(reference.targetEntityAlias()).isEqualTo("customer");
        assertThat(reference.projections()).anySatisfy(projection -> {
            assertThat(projection.targetField()).isEqualTo("title");
            assertThat(projection.outputField()).isEqualTo("customerTitle");
        });
        assertThat(reference.integrity().onTargetUnavailable()).isEqualTo(ReferenceTargetUnavailablePolicy.RESTRICT);
        assertThat(reference.projections())
                .containsExactly(new DynamicReferenceProjectionDescriptor("title", "customerTitle"));
        assertThat(descriptor.entities().get(1).fields().get(1).reference())
                .satisfies(fieldReference -> {
                    assertThat(fieldReference.sourceField()).isEqualTo("customerId");
                    assertThat(fieldReference.targetModuleAlias()).isEqualTo("crm.customer");
                    assertThat(fieldReference.targetEntityAlias()).isEqualTo("customer");
                });
    }

    @Test
    void relationCascadeShouldBeDerivedFromForeignKeyReferenceIntegrity() {
        ModuleDefinition module = ModuleDefinition.builder("crm.customer", "Customer")
                .entities(List.of(
                        new EntityDefinition("customer", "crm_customer", "Customer", List.of(FieldDefinition.titleField())),
                        new EntityDefinition("contact", "crm_contact", "Contact", List.of(
                                FieldDefinition.string("customerId", "Customer")))
                ))
                .relations(List.of(EntityRelationDefinition.child("contacts", "customer", "contact", "customerId")))
                .references(List.of(EntityReferenceDefinition.to("contact", "customerId", "crm.customer.customer")
                        .withIntegrity(new ReferenceIntegrityPolicy(
                                ReferenceTargetUnavailablePolicy.CASCADE_DELETE))))
                .build();

        assertThat(DynamicModuleDescriptor.from(module).relations().getFirst().cascadeOnParentUnavailable()).isTrue();
    }

    @Test
    void shouldExposeDerivedPermissionMountForDynamicActions() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                        FieldDefinition.titleField()
                ))))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(
                        new EntityActionDefinition("contract", "submit", "Submit", true, EntityActionLevel.RECORD,
                                EntityActionCategory.CUSTOM, EntityActionAccessMode.AUTH_REQUIRED,
                                true, true, "view", null, null,
                                EntityActionExecutorType.SERVICE, "contractSubmit")
                ))
                .build();

        DynamicModuleDescriptor descriptor = DynamicModuleDescriptor.from(module);

        assertThat(descriptor.actions().stream()
                .filter(action -> action.code().equals("view"))
                .findFirst())
                .get()
                .satisfies(action -> assertThat(action.permission())
                        .isEqualTo(new ActionPermissionDescriptor("sales.contract:view", true, false, null, null)));
        assertThat(descriptor.actions().stream()
                .filter(action -> action.code().equals("submit"))
                .findFirst())
                .get()
                .satisfies(action -> assertThat(action.permission())
                        .isEqualTo(new ActionPermissionDescriptor("sales.contract:view", true, true,
                                "view", "sales.contract:view")));
        assertThat(ActionPermissionDescriptor.of("sales.contract",
                new DynamicActionDescriptor("customExport", "Export", true, EntityActionLevel.LIST, EntityActionCategory.CUSTOM,
                        EntityActionAccessMode.AUTH_REQUIRED, true, false, "query", false, null,
                        EntityActionExecutorType.SERVICE, "customExport")))
                .isEqualTo(new ActionPermissionDescriptor("sales.contract:view", true, false,
                        "query", "sales.contract:view"));
    }

    @Test
    void shouldExposeMainEntityActionsAsModuleActions() {
        ModuleDefinition module = ModuleDefinition.builder("crm.customer", "Customer")
                .entities(List.of(
                        new EntityDefinition("contact", "crm_contact", "Contact",
                                List.of(FieldDefinition.titleField())),
                        new EntityDefinition("customer", "crm_customer", "Customer",
                                List.of(FieldDefinition.titleField()),
                                Set.of(EntityCapability.CRUD, EntityCapability.DATA_SCOPE))
                ))
                .relations(List.of(EntityRelationDefinition.child(
                        "contacts", "customer", "contact", "customerId")))
                .actions(List.of(
                        new EntityActionDefinition("customer", "create", "新建客户", true),
                        new EntityActionDefinition("contact", "exportContact", "导出联系人", true)
                ))
                .mainEntityAlias("customer")
                .build();

        DynamicModuleDescriptor descriptor = DynamicModuleDescriptor.from(module);

        assertThat(descriptor.actions())
                .extracting(DynamicActionDescriptor::code)
                .contains("create")
                .contains("exportContact");
        assertThat(descriptor.actions())
                .extracting(DynamicActionDescriptor::code)
                .contains("query");
        assertThat(descriptor.actions().stream().filter(action -> action.code().equals("create")).findFirst())
                .get()
                .extracting(DynamicActionDescriptor::title)
                .isEqualTo("新建客户");
        List<String> contactActions = descriptor.entities().stream()
                .filter(entity -> entity.entityAlias().equals("contact"))
                .findFirst()
                .get()
                .actions().stream()
                .map(DynamicActionDescriptor::code)
                .toList();
        List<String> customerActions = descriptor.entities().stream()
                .filter(entity -> entity.entityAlias().equals("customer"))
                .findFirst()
                .get()
                .actions().stream()
                .map(DynamicActionDescriptor::code)
                .toList();
        assertThat(contactActions).contains("exportContact");
        assertThat(customerActions)
                .contains("create")
                .doesNotContain("exportContact");
    }

    @Test
    void shouldUseExplicitMainEntityAsModuleActionBaseAndExposeConfiguredChildActions() {
        ModuleDefinition module = ModuleDefinition.builder("crm.customer", "Customer")
                .entities(List.of(
                        new EntityDefinition("contact", "crm_contact", "Contact",
                                List.of(FieldDefinition.titleField())),
                        new EntityDefinition("customer", "crm_customer", "Customer",
                                List.of(FieldDefinition.titleField()),
                                Set.of(EntityCapability.CRUD, EntityCapability.DATA_SCOPE))
                ))
                .actions(List.of(
                        new EntityActionDefinition("contact", "exportContact", "导出联系人", true),
                        new EntityActionDefinition("customer", "approveCustomer", "审核客户", true)
                ))
                .mainEntityAlias("customer")
                .build();

        DynamicModuleDescriptor descriptor = DynamicModuleDescriptor.from(module);

        assertThat(descriptor.actions())
                .extracting(DynamicActionDescriptor::code)
                .contains("approveCustomer")
                .contains("exportContact");
    }

    @Test
    void shouldExposeCapabilitySpecificStandardActions() {
        ModuleDefinition module = new ModuleDefinition(
                "crm.customer",
                "Customer",
                List.of(
                        new EntityDefinition("customer", "crm_customer", "Customer", List.of(
                                FieldDefinition.titleField(),
                                FieldDefinition.string("parentId", "Parent"),
                                FieldDefinition.integer("sortOrder", "Sort"),
                                FieldDefinition.bool("enabled", "Enabled")
                        ), Set.of(EntityCapability.TREE, EntityCapability.ENABLE))
                )
        );

        DynamicEntityDescriptor entity = DynamicModuleDescriptor.from(module).entities().getFirst();

        assertThat(entity.actions())
                .extracting(DynamicActionDescriptor::code)
                .contains("tree", "sort", "enable", "disable");
        assertThat(entity.actions().stream()
                .filter(action -> action.code().equals("delete"))
                .findFirst())
                .get()
                .satisfies(action -> {
                    assertThat(action.actionLevel()).isEqualTo(net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel.RECORD);
                });
        assertThat(entity.actions().stream()
                .filter(action -> action.code().equals("create"))
                .findFirst())
                .get()
                .extracting(DynamicActionDescriptor::actionLevel)
                .isEqualTo(net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel.LIST);
    }

    @Test
    void shouldApplyConfiguredActionGovernance() {
        ModuleDefinition module = ModuleDefinition.builder("crm.customer", "Customer")
                .entities(List.of(
                        new EntityDefinition("customer", "crm_customer", "Customer",
                                List.of(FieldDefinition.titleField()),
                                Set.of(EntityCapability.CRUD, EntityCapability.DATA_SCOPE))
                ))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .actions(List.of(
                        new EntityActionDefinition("customer", "create", "新建客户", true),
                        new EntityActionDefinition("customer", "delete", "删除客户", false)
                                .availableWhen("{status} == 'draft'", "只有草稿客户可删除"),
                        new EntityActionDefinition("customer", "exportData", "导出", true),
                        new EntityActionDefinition("customer", "archiveSelected", "批量归档", true, EntityActionLevel.BATCH,
                                null, null, null, null, null, null, null, null, null)
                ))
                .build();

        List<DynamicActionDescriptor> actions = DynamicModuleDescriptor.from(module).entities().getFirst().actions();

        assertThat(actions.stream().filter(action -> action.code().equals("create")).findFirst())
                .get()
                .satisfies(action -> {
                    assertThat(action.title()).isEqualTo("新建客户");
                    assertThat(action.enabled()).isTrue();
                });
        assertThat(actions.stream().filter(action -> action.code().equals("delete")).findFirst())
                .get()
                .satisfies(action -> {
                    assertThat(action.enabled()).isFalse();
                    assertThat(action.actionAuth()).isTrue();
                    assertThat(action.dataAuth()).isTrue();
                    assertThat(action.availabilityCondition()).isTrue();
                    assertThat(action.unavailableMessage()).isEqualTo("只有草稿客户可删除");
                });
        assertThat(actions.stream().filter(action -> action.code().equals("exportData")).findFirst())
                .get()
                .satisfies(action -> {                    assertThat(action.category().name()).isEqualTo("CUSTOM");
                });
        assertThat(actions.stream().filter(action -> action.code().equals("archiveSelected")).findFirst())
                .get()
                .extracting(DynamicActionDescriptor::actionLevel)
                .isEqualTo(EntityActionLevel.BATCH);
    }

    @Test
    void shouldExposeExplicitViewDefinition() {
        ModuleDefinition module = ModuleDefinition.builder("crm.customer", "Customer")
                .entities(List.of(
                        new EntityDefinition("customer", "crm_customer", "Customer", List.of(
                                FieldDefinition.titleField(),
                                FieldDefinition.string("status", "Status")
                                        .dictionary("crm", "customer_status")
                                        .defaultUiType("select"),
                                FieldDefinition.of("tags", FieldType.JSON, "Tags")
                                        .dictionary("crm", "customer_tag", OptionSelectionMode.MULTIPLE),
                                FieldDefinition.text("description", "Description")
                        ))
                ))
                .relations(List.of())
                .references(List.of())
                .views(List.of(new EntityViewDefinition(
                        "customer",
                        EntityViewType.FORM,
                        "Customer form",
                        List.of(
                                new EntityViewFieldDefinition("title").title("Customer name"),
                                new EntityViewFieldDefinition("status").control(ViewControlType.SELECT).readOnly(true),
                                new EntityViewFieldDefinition("tags").control(ViewControlType.SELECT),
                                new EntityViewFieldDefinition("description").hidden().control(ViewControlType.TEXTAREA)
                        )
                )))
                .build();

        List<DynamicViewDescriptor> views = DynamicModuleDescriptor.from(module).entities().getFirst().views();
        DynamicViewDescriptor listView = views.getFirst();
        DynamicViewDescriptor formView = views.get(1);

        assertThat(listView.viewType()).isEqualTo(EntityViewType.LIST);
        assertThat(listView.fields())
                .extracting(DynamicViewFieldDescriptor::fieldName)
                .containsExactly("title", "status", "tags", "description");
        assertThat(formView.viewType()).isEqualTo(EntityViewType.FORM);
        assertThat(formView.title()).isEqualTo("Customer form");
        assertThat(formView.fields())
                .extracting(DynamicViewFieldDescriptor::fieldName)
                .containsExactly("title", "status", "tags", "description");
        assertThat(formView.fields().getFirst().title()).isEqualTo("Customer name");
        assertThat(formView.fields().get(1).controlType()).isEqualTo(ViewControlType.SELECT);
        assertThat(formView.fields().get(1).fieldUiControlAlias()).isEqualTo("select");
        assertThat(formView.fields().get(1).readOnly()).isTrue();
        assertThat(formView.fields().get(2).controlType()).isEqualTo(ViewControlType.MULTI_SELECT);
        assertThat(formView.fields().get(3).visible()).isFalse();
    }

    @Test
    void shouldNeverRelaxModelRequiredFieldInViewDescriptor() {
        ModuleDefinition module = ModuleDefinition.builder("crm.customer", "Customer")
                .entities(List.of(
                        new EntityDefinition("customer", "crm_customer", "Customer", List.of(
                                FieldDefinition.string("code", "Code").required(),
                                FieldDefinition.string("remark", "Remark")
                        ))
                ))
                .relations(List.of())
                .references(List.of())
                .views(List.of(new EntityViewDefinition(
                        "customer",
                        EntityViewType.FORM,
                        "Customer form",
                        List.of(
                                new EntityViewFieldDefinition("code").required(false),
                                new EntityViewFieldDefinition("remark").required(true)
                        )
                )))
                .build();

        DynamicViewDescriptor formView = DynamicModuleDescriptor.from(module).entities().getFirst().views().get(1);

        assertThat(formView.fields().getFirst().required()).isTrue();
        assertThat(formView.fields().get(1).required()).isTrue();
    }

    @Test
    void shouldExposeDynamicActionDefaultGrantPolicy() {
        ModuleDefinition module = ModuleDefinition.builder("crm.customer", "Customer")
                .entities(List.of(new EntityDefinition("customer", "crm_customer", "Customer", List.of(
                        FieldDefinition.titleField()
                ), Set.of(EntityCapability.DATA_SCOPE))))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(new EntityActionDefinition("customer", "follow", "关注", true, EntityActionLevel.RECORD,
                        EntityActionCategory.CUSTOM, EntityActionAccessMode.AUTH_REQUIRED,
                        true, true, ActionDefaultGrantPolicy.OWNER, null, null, null,
                        EntityActionExecutorType.SERVICE, "followExecutor")))
                .build();

        DynamicActionDescriptor action = DynamicModuleDescriptor.from(module)
                .entities().getFirst()
                .actions().stream()
                .filter(item -> item.code().equals("follow"))
                .findFirst()
                .orElseThrow();

        assertThat(action.defaultGrantPolicy()).isEqualTo(ActionDefaultGrantPolicy.OWNER);
    }

    @Test
    void shouldExposeConfiguredStandardActionDefaultGrantPolicy() {
        ModuleDefinition module = ModuleDefinition.builder("crm.customer", "Customer")
                .entities(List.of(new EntityDefinition("customer", "crm_customer", "Customer", List.of(
                        FieldDefinition.titleField()
                ), Set.of(EntityCapability.CRUD, EntityCapability.DATA_SCOPE))))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(new EntityActionDefinition("customer", "view", "查看", true, EntityActionLevel.RECORD,
                        EntityActionCategory.STANDARD, EntityActionAccessMode.AUTH_REQUIRED,
                        true, true, ActionDefaultGrantPolicy.OWNER, null, null, null,
                        EntityActionExecutorType.STANDARD, null)))
                .build();

        DynamicActionDescriptor action = DynamicModuleDescriptor.from(module)
                .entities().getFirst()
                .actions().stream()
                .filter(item -> item.code().equals("view"))
                .findFirst()
                .orElseThrow();

        assertThat(action.defaultGrantPolicy()).isEqualTo(ActionDefaultGrantPolicy.OWNER);
        assertThat(action.dataAuth()).isTrue();
    }
}
