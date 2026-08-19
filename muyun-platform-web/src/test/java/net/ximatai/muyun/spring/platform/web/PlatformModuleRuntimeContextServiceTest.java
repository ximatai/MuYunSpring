package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;

import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.PlatformManagedProtectionAbility;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.RecordActionAvailabilityDecision;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionAvailability;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageBootstrapService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshot;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedPageConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedUiField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlValueShape;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformModuleRuntimeContextServiceTest {
    @Test
    void shouldResolveStaticPageNavigatorWithCurrentRequestFacts() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        when(moduleService.resolveVisibleModule("iam.organization"))
                .thenReturn(module("iam.organization", "组织管理", ModuleKind.STATIC));
        when(actionService.listByModuleAliases(List.of("iam.organization"))).thenReturn(List.of());
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", "iam.organization", "组织管理")
                .parentModuleAlias(null)
                .navigatorSourceCapabilities(Set.of(NavigatorSourceCapability.REFERENCE_TREE))
                .actions(List.of(StaticModuleActionDefinition.platformAction(PlatformAction.VIEW)))
                .uiDefinition(ModuleUiDefinition.builder("iam.organization")
                        .page(PageTemplates.listDetailCard(page -> page
                                .navigator(navigator -> navigator.level("organization", level -> level
                                        .tree("iam.organization", "所属组织", "搜索组织")))
                                .list(list -> list.fields(fields -> fields.field("title")))
                                .detail(detail -> detail.editor(fields -> fields.field("title")))))
                        .build())
                .build();
        AtomicReference<PageNavigatorResolutionContext> resolved = new AtomicReference<>();
        PageNavigatorResolver resolver = context -> {
            resolved.set(context);
            return Set.of();
        };
        PlatformModuleRuntimeContextService service = new PlatformModuleRuntimeContextService(
                moduleService, actionService, new StaticModuleDefinitionCatalog(List.of(definition)), null,
                null, null, allowAllPolicy(), List.of(), resolver,
                moduleAlias -> Set.of(NavigatorSourceCapability.REFERENCE_TREE));

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "tenant-admin", "tenant-1", "organization-1"))) {
            PlatformModuleRuntimeContext runtimeContext = service.context("iam.organization");

            assertThat(runtimeContext.navigatorSourceCapabilities())
                    .containsExactly(NavigatorSourceCapability.REFERENCE_TREE);

            assertThat(runtimeContext.uiDescriptor().page()).satisfies(page -> {
                assertThat(page.navigator()).isNull();
                assertThat(page.list().fields().fields()).singleElement()
                        .satisfies(field -> assertThat(field.fieldRef().fieldName()).isEqualTo("title"));
            });
        }

        assertThat(resolved.get()).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo("iam.organization");
            assertThat(context.moduleKind()).isEqualTo(ModuleKind.STATIC);
            assertThat(context.currentUser()).satisfies(user -> {
                assertThat(user.tenantId()).isEqualTo("tenant-1");
                assertThat(user.organizationId()).isEqualTo("organization-1");
            });
            assertThat(context.candidate().template()).isEqualTo(ModulePageTemplate.LIST_DETAIL_CARD);
            assertThat(context.candidate().navigator().levels()).singleElement()
                    .satisfies(level -> assertThat(level.key()).isEqualTo("organization"));
        });
    }

    @Test
    void shouldResolveStaticPageNavigatorBackedByPublishedDynamicReferenceModule() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        DynamicRecordService dynamicRecordService = mock(DynamicRecordService.class);
        when(moduleService.resolveVisibleModule("sales.contract"))
                .thenReturn(module("sales.contract", "合同", ModuleKind.STATIC));
        when(actionService.listByModuleAliases(List.of("sales.contract"))).thenReturn(List.of());
        when(dynamicRecordService.describe("crm.customer")).thenReturn(new DynamicModuleDescriptor(
                "crm.customer", "客户", "customer", List.of(),
                List.of(dynamicEntity("customer", "CRUD", "REFERENCE")), List.of(), List.of(), List.of()));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.contract", "合同")
                .parentModuleAlias(null)
                .actions(List.of(StaticModuleActionDefinition.platformAction(PlatformAction.VIEW)))
                .uiDefinition(ModuleUiDefinition.builder("sales.contract")
                        .page(PageTemplates.listDetailCard(page -> page
                                .navigator(navigator -> navigator.level("customer", level -> level
                                        .microList("crm.customer", "客户", "搜索客户")))
                                .list(list -> list.fields(fields -> fields.field("title")))
                                .detail(detail -> detail.editor(fields -> fields.field("title")))))
                        .build())
                .build();
        StaticModuleDefinitionCatalog catalog = new StaticModuleDefinitionCatalog(List.of(definition));
        PlatformModuleRuntimeContextService service = new PlatformModuleRuntimeContextService(
                moduleService, actionService, catalog, null, null, null, allowAllPolicy(), List.of(),
                new DeclaredPageNavigatorResolver(),
                new PlatformPageNavigatorSourceCapabilityResolver(catalog, dynamicRecordService));

        PlatformModuleRuntimeContext context = service.context("sales.contract");

        assertThat(context.uiDescriptor().page().navigator().levels()).singleElement()
                .satisfies(level -> assertThat(level.sourceModuleAlias()).isEqualTo("crm.customer"));
    }

    @Test
    void shouldNotResolveNavigatorWhenModuleDoesNotDeclareAPage() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        when(moduleService.resolveVisibleModule("iam.organization"))
                .thenReturn(module("iam.organization", "组织管理", ModuleKind.STATIC));
        when(actionService.listByModuleAliases(List.of("iam.organization"))).thenReturn(List.of());
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", "iam.organization", "组织管理")
                .parentModuleAlias(null)
                .actions(List.of(StaticModuleActionDefinition.platformAction(PlatformAction.VIEW)))
                .build();
        PageNavigatorResolver resolver = context -> {
            throw new AssertionError("navigator resolver must not be called without a page candidate");
        };
        PlatformModuleRuntimeContextService service = new PlatformModuleRuntimeContextService(
                moduleService, actionService, new StaticModuleDefinitionCatalog(List.of(definition)), null,
                null, null, allowAllPolicy(), List.of(), resolver);

        PlatformModuleRuntimeContext runtimeContext = service.context("iam.organization");

        assertThat(runtimeContext.uiDescriptor()).isNotNull();
        assertThat(runtimeContext.uiDescriptor().page()).isNull();
    }

    @Test
    void shouldComposeStaticCapabilitiesFromActionsAndEntities() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        PlatformModule module = module("iam.organization", "组织管理", ModuleKind.STATIC);
        when(moduleService.resolveVisibleModule("iam.organization")).thenReturn(module);
        when(actionService.listByModuleAliases(List.of("iam.organization"))).thenReturn(List.of());
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", "iam.organization", "组织管理")
                                                    .parentModuleAlias(null)
                                                    .entry(ModuleEntryType.ROUTE, "/iam/organizations", null)
                                                    .capabilities(Set.of())
                                                    .actions(List.of(
                        StaticModuleActionDefinition.platformAction(PlatformAction.MENU),
                        StaticModuleActionDefinition.platformAction(PlatformAction.VIEW),
                        StaticModuleActionDefinition.platformAction(PlatformAction.CREATE),
                        StaticModuleActionDefinition.platformAction(PlatformAction.UPDATE),
                        StaticModuleActionDefinition.platformAction(PlatformAction.DELETE),
                        StaticModuleActionDefinition.platformAction(PlatformAction.TREE),
                        StaticModuleActionDefinition.platformAction(PlatformAction.ENABLE),
                        StaticModuleActionDefinition.platformAction(PlatformAction.DISABLE),
                        StaticModuleActionDefinition.platformAction(PlatformAction.RECYCLE_BIN_QUERY)
                ))
                                                    .entities(List.of())
                                                    .uiDefinition(ModuleUiDefinition.builder("iam.organization")
                        .page(PageTemplates.listDetailCard(page -> page
                                .list(list -> list.fields(fields -> fields
                                        .title("组织列表")
                                        .field("title", field -> field.label("组织名称"))))
                                .detail(detail -> detail.editor(fields -> fields.field("title")))
                                .traits(traits -> traits.standardCrud())))
                        .typedTextConfirmation("delete", "title")
                        .build())
                                                    .build();
        PlatformModuleRuntimeContextService service = new PlatformModuleRuntimeContextService(
                moduleService,
                actionService,
                new StaticModuleDefinitionCatalog(List.of(definition)),
                null,
                null,
                null,
                allowAllPolicy()
        );

        PlatformModuleRuntimeContext context = service.context("iam.organization");

        assertThat(context.moduleAlias()).isEqualTo("iam.organization");
        assertThat(context.entryType()).isEqualTo(ModuleEntryType.ROUTE);
        assertThat(context.entryRoute()).isEqualTo("/iam/organizations");
        assertThat(context.capabilities()).contains(
                EntityCapability.CRUD,
                EntityCapability.SOFT_DELETE,
                EntityCapability.LIFECYCLE,
                EntityCapability.CACHE,
                EntityCapability.TREE,
                EntityCapability.SORT,
                EntityCapability.ENABLE,
                EntityCapability.RECYCLE_BIN
        );
        assertThat(context.abilities()).contains(
                "crud",
                "softDelete",
                "lifecycle",
                "cache",
                "tree",
                "sort",
                "enable",
                "recycleBin"
        );
        assertThat(context.actions()).extracting(PlatformModuleRuntimeAction::actionCode)
                .containsExactly("menu", "view", "create", "update", "delete", "tree", "enable", "disable",
                        "recycleBinQuery");
        assertThat(context.actions()).allSatisfy(action -> assertThat(action.authorized()).isTrue());
        assertThat(context.uiDescriptor()).isNotNull();
        assertThat(context.uiDescriptor().schemaVersion()).isEqualTo(ResolvedModuleUiDescriptor.SCHEMA_VERSION);
        assertThat(context.uiDescriptor().moduleKind()).isEqualTo(ModuleKind.STATIC);
        assertThat(context.uiDescriptor().title()).isEqualTo("组织管理");
        assertThat(context.uiDescriptor().page().list().fields().fields()).singleElement()
                .satisfies(field -> {
                    assertThat(field.fieldRef().fieldName()).isEqualTo("title");
                    assertThat(field.label()).isEqualTo("组织名称");
                });
        assertThat(context.uiDescriptor().actions()).singleElement()
                .satisfies(action -> {
                    assertThat(action.actionCode()).isEqualTo("delete");
                    assertThat(action.confirmation().mode()).isEqualTo("typedText");
                    assertThat(action.confirmation().requiredField()).isEqualTo("title");
                });
    }

    @Test
    void shouldNotExposeSecondaryEntityCapabilitiesAsModuleAbilities() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        when(moduleService.resolveVisibleModule("sales.contract"))
                .thenReturn(module("sales.contract", "合同", ModuleKind.STATIC));
        when(actionService.listByModuleAliases(List.of("sales.contract"))).thenReturn(List.of());
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.contract", "合同")
                                                    .parentModuleAlias(null)
                                                    .entry(ModuleEntryType.ROUTE, "/sales/contracts", null)
                                                    .capabilities(Set.of())
                                                    .actions(List.of(StaticModuleActionDefinition.platformAction(PlatformAction.VIEW)))
                                                    .entities(List.of(
                        entity("contract", Set.of(EntityCapability.CRUD)),
                        entity("contractLine", Set.of(EntityCapability.CRUD, EntityCapability.TREE,
                                EntityCapability.ENABLE))
                ))
                                                    .build();
        PlatformModuleRuntimeContextService service = new PlatformModuleRuntimeContextService(
                moduleService,
                actionService,
                new StaticModuleDefinitionCatalog(List.of(definition)),
                null,
                null,
                null,
                allowAllPolicy()
        );

        PlatformModuleRuntimeContext context = service.context("sales.contract");

        assertThat(context.mainEntityAlias()).isEqualTo("contract");
        assertThat(context.abilities()).contains("crud");
        assertThat(context.abilities()).doesNotContain("tree", "enable");
    }

    @Test
    void shouldPreferPersistedModuleActionsAndExposeAuthorizationResult() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        when(moduleService.resolveVisibleModule("iam.organization"))
                .thenReturn(module("iam.organization", "组织管理", ModuleKind.STATIC));
        PlatformModuleAction view = action("iam.organization", PlatformAction.VIEW);
        PlatformModuleAction enable = action("iam.organization", PlatformAction.ENABLE);
        when(actionService.listByModuleAliases(List.of("iam.organization"))).thenReturn(List.of(view, enable));
        ActionExecutionPolicyService policyService = context -> {
            if (PlatformAction.ENABLE.matches(context.actionCode())) {
                throw new PlatformAccessDeniedException("denied");
            }
        };
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", "iam.organization", "组织管理")
                                                    .parentModuleAlias(null)
                                                    .actions(List.of(StaticModuleActionDefinition.platformAction(PlatformAction.TREE)))
                                                    .build();
        PlatformModuleRuntimeContextService service = new PlatformModuleRuntimeContextService(
                moduleService,
                actionService,
                new StaticModuleDefinitionCatalog(List.of(definition)),
                null,
                null,
                null,
                policyService
        );

        PlatformModuleRuntimeContext context = service.context("iam.organization");

        assertThat(context.actions()).extracting(PlatformModuleRuntimeAction::actionCode)
                .containsExactly("view", "enable");
        assertThat(context.actions()).filteredOn(action -> "view".equals(action.actionCode()))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.authorized()).isTrue();
                    assertThat(action.authorizationDecision()).isEqualTo(ActionAuthorizationResult.DECISION_ALLOWED);
                });
        assertThat(context.actions()).filteredOn(action -> "enable".equals(action.actionCode()))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.authorized()).isFalse();
                    assertThat(action.authorizationDecision())
                            .isEqualTo(PlatformModuleRuntimeContextService.DECISION_ACCESS_DENIED);
                });
        assertThat(context.capabilities()).contains(EntityCapability.ENABLE);
        assertThat(context.abilities()).contains("enable");
    }

    @Test
    void shouldMergePersistedDynamicActionsWithDescriptorActions() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        DynamicRecordService dynamicRecordService = mock(DynamicRecordService.class);
        when(moduleService.resolveVisibleModule("sales.contract"))
                .thenReturn(module("sales.contract", "合同", ModuleKind.DYNAMIC));
        PlatformModuleAction persistedUpdate = action("sales.contract", PlatformAction.UPDATE);
        persistedUpdate.setTitle("编辑合同");
        PlatformModuleAction disabledDelete = action("sales.contract", PlatformAction.DELETE);
        disabledDelete.setEnabled(Boolean.FALSE);
        when(actionService.listByModuleAliases(List.of("sales.contract")))
                .thenReturn(List.of(persistedUpdate, disabledDelete));
        when(dynamicRecordService.describe("sales.contract")).thenReturn(new DynamicModuleDescriptor(
                "sales.contract",
                "合同",
                "contract",
                List.of(
                        dynamicAction(PlatformAction.VIEW),
                        dynamicAction(PlatformAction.UPDATE),
                        dynamicAction(PlatformAction.DELETE)
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        PlatformModuleRuntimeContextService service = new PlatformModuleRuntimeContextService(
                moduleService,
                actionService,
                new StaticModuleDefinitionCatalog(List.of()),
                dynamicRecordService,
                null,
                null,
                allowAllPolicy()
        );

        PlatformModuleRuntimeContext context = service.context("sales.contract");

        assertThat(context.actions()).extracting(PlatformModuleRuntimeAction::actionCode)
                .containsExactly("view", "update");
        assertThat(context.actions()).filteredOn(action -> "update".equals(action.actionCode()))
                .singleElement()
                .satisfies(action -> assertThat(action.title()).isEqualTo("编辑合同"));
    }

    @Test
    void shouldNotExposeSecondaryDynamicEntityCapabilitiesAsModuleAbilities() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        DynamicRecordService dynamicRecordService = mock(DynamicRecordService.class);
        when(moduleService.resolveVisibleModule("sales.contract"))
                .thenReturn(module("sales.contract", "合同", ModuleKind.DYNAMIC));
        when(actionService.listByModuleAliases(List.of("sales.contract"))).thenReturn(List.of());
        when(dynamicRecordService.describe("sales.contract")).thenReturn(new DynamicModuleDescriptor(
                "sales.contract",
                "合同",
                "contract",
                List.of(dynamicAction(PlatformAction.VIEW)),
                List.of(
                        dynamicEntity("contract", "CRUD"),
                        dynamicEntity("contractLine", "CRUD", "TREE", "ENABLE")
                ),
                List.of(new DynamicRelationDescriptor(
                        "subLines", "contractLine", "contractSubLine", "contractLineId", false, false)),
                List.of(dynamicReference("contractLine", "productId")),
                List.of()
        ));
        PlatformModuleRuntimeContextService service = new PlatformModuleRuntimeContextService(
                moduleService,
                actionService,
                new StaticModuleDefinitionCatalog(List.of()),
                dynamicRecordService,
                null,
                null,
                allowAllPolicy()
        );

        PlatformModuleRuntimeContext context = service.context("sales.contract");

        assertThat(context.mainEntityAlias()).isEqualTo("contract");
        assertThat(context.abilities()).contains("crud");
        assertThat(context.abilities()).doesNotContain("tree", "enable", "childRelation", "reference",
                "referenceDependency");
    }

    @Test
    void shouldExposeDynamicUiDescriptorFromPublishedPageSnapshot() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        DynamicRecordService dynamicRecordService = mock(DynamicRecordService.class);
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        PlatformPageBootstrapService bootstrapService = mock(PlatformPageBootstrapService.class);
        when(moduleService.resolveVisibleModule("crm.customer"))
                .thenReturn(module("crm.customer", "客户", ModuleKind.DYNAMIC));
        when(actionService.listByModuleAliases(List.of("crm.customer"))).thenReturn(List.of());
        when(dynamicRecordService.describe("crm.customer")).thenReturn(new DynamicModuleDescriptor(
                "crm.customer",
                "客户",
                "customer",
                List.of(dynamicAction(PlatformAction.QUERY), dynamicAction(PlatformAction.CREATE)),
                List.of(dynamicEntity("customer", List.of(
                        DynamicFieldDescriptor.from(FieldDefinition.string("name", "客户名称")),
                        DynamicFieldDescriptor.from(FieldDefinition.bool("enabled", "启用状态")),
                        DynamicFieldDescriptor.from(FieldDefinition.timestamp("createdAt", "创建时间")),
                        DynamicFieldDescriptor.from(FieldDefinition.longInteger("storageBytes", "存储大小")),
                        DynamicFieldDescriptor.from(FieldDefinition.string("organizationId", "所属机构"),
                                dynamicReference("customer", "organizationId"))), "CRUD")),
                List.of(),
                List.of(),
                List.of()
        ));
        PlatformUiSet listSet = uiSet("set-list", "crm.customer", "customer_list", PlatformUiSetType.LIST);
        PlatformUiSet formSet = uiSet("set-form", "crm.customer", "customer_form", PlatformUiSetType.FORM);
        PlatformUiConfig listConfig = uiConfig("ui-list-web", "set-list", "客户列表");
        listConfig.setLayoutJson("""
                {"template":"LIST_DETAIL_CARD","traits":[],"navigator":{"contextBindings":[{
                  "source":"NAVIGATOR","sourceKey":"organization","target":"LIST_QUERY","targetKey":"organizationId"
                }],"levels":[{
                  "key":"organization","kind":"TREE","sourceModuleAlias":"base.product"
                }]}}""");
        PlatformUiConfig formConfig = uiConfig("ui-form-web", "set-form", "客户表单");
        PlatformPageConfigSnapshot snapshot = new PlatformPageConfigSnapshot(
                "crm.customer",
                List.of(listSet, formSet),
                List.of(listConfig, formConfig),
                List.of(),
                List.of(),
                List.of()
        );
        PlatformResolvedPageConfig resolvedConfig = new PlatformResolvedPageConfig(List.of(
                resolvedField("ui-list-web", "field-name", null, "name", "客户名称", "160", "left", "date_range"),
                resolvedField("ui-list-web", "field-enabled", null, "enabled", "启用状态", "120", "center"),
                resolvedField("ui-list-web", "field-created-at", null, "createdAt", "创建时间", "180", "left"),
                resolvedField("ui-list-web", "field-storage-bytes", null, "storageBytes", "存储大小", "120", "right",
                        "file_size"),
                resolvedField("ui-form-web", "field-name", null, "name", "客户名称", null, null),
                resolvedField("ui-form-web", "field-organization", null, "organizationId", "所属机构", null, null)
        ), List.of());
        when(snapshotService.snapshot("crm.customer")).thenReturn(snapshot);
        when(bootstrapService.resolveConfig(snapshot, PlatformUiClientType.WEB)).thenReturn(resolvedConfig);
        FieldUiControlService fieldUiControlService = mock(FieldUiControlService.class);
        FieldUiControlPropertyService propertyService = mock(FieldUiControlPropertyService.class);
        FieldUiControlBindingService bindingService = mock(FieldUiControlBindingService.class);
        FieldUiControl dateRange = new FieldUiControl();
        dateRange.setAlias("date_range");
        dateRange.setEnabled(Boolean.TRUE);
        dateRange.setValueShape(FieldUiControlValueShape.COMPOSITE);
        dateRange.setRendererType(ViewControlType.DATE);
        FieldUiControlProperty property = new FieldUiControlProperty();
        property.setFieldUiControlAlias("date_range");
        property.setAttributeAlias("format");
        property.setDefaultValue("YYYY-MM-DD");
        FieldUiControlBinding binding = new FieldUiControlBinding();
        binding.setFieldUiControlAlias("date_range");
        binding.setValueKey("end");
        binding.setValueFieldSpecAlias("date");
        when(fieldUiControlService.listEnabledByAliases(List.of("date_range"))).thenReturn(List.of(dateRange));
        when(propertyService.listByFieldUiControlAliases(List.of("date_range"))).thenReturn(List.of(property));
        when(bindingService.listByFieldUiControlAliases(List.of("date_range"))).thenReturn(List.of(binding));
        AtomicReference<PageNavigatorResolutionContext> resolvedNavigator = new AtomicReference<>();
        PlatformModuleRuntimeContextService service = new PlatformModuleRuntimeContextService(
                moduleService,
                actionService,
                new StaticModuleDefinitionCatalog(List.of()),
                dynamicRecordService,
                snapshotService,
                bootstrapService,
                allowAllPolicy(),
                List.of(),
                navigatorContext -> {
                    resolvedNavigator.set(navigatorContext);
                    return navigatorContext.candidate().navigator().levels().stream()
                            .map(ResolvedPageNavigatorLevelDescriptor::key)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                }, null, fieldUiControlService, propertyService, bindingService
        );

        PlatformModuleRuntimeContext context = service.context("crm.customer");

        assertThat(context.uiDescriptor()).isNotNull();
        assertThat(context.uiDescriptor().moduleKind()).isEqualTo(ModuleKind.DYNAMIC);
        assertThat(context.uiDescriptor().moduleAlias()).isEqualTo("crm.customer");
        assertThat(context.uiDescriptor().page()).isNotNull();
        assertThat(resolvedNavigator.get()).satisfies(navigatorContext -> {
            assertThat(navigatorContext.moduleAlias()).isEqualTo("crm.customer");
            assertThat(navigatorContext.moduleKind()).isEqualTo(ModuleKind.DYNAMIC);
            assertThat(navigatorContext.candidate()).isEqualTo(context.uiDescriptor().page());
        });
        ResolvedViewDescriptor pageList = context.uiDescriptor().page().list().fields();
        assertThat(pageList.fields().getFirst().fieldControl()).isEqualTo(new ResolvedFieldControlDescriptor(
                "date_range", "DATE", "COMPOSITE", java.util.Map.of("format", "YYYY-MM-DD"),
                List.of(new ResolvedFieldControlBindingDescriptor("end", "date"))));
        assertThat(context.uiDescriptor().page().navigator().levels()).singleElement().satisfies(level -> {
            assertThat(level.sourceModuleAlias()).isEqualTo("base.product");
        });
        assertThat(context.uiDescriptor().page().navigator().contextBindings()).containsExactly(
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "organization",
                        PageContextTarget.LIST_QUERY, "organizationId", null));
        assertThat(pageList).satisfies(view -> {
                    assertThat(view.viewKind()).isEqualTo(ModuleViewKind.LIST);
                    assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                            .containsExactly("name", "enabled", "createdAt", "storageBytes");
                    assertThat(view.fields()).extracting(ResolvedViewFieldDescriptor::valueType)
                            .containsExactly(FieldValueType.STRING, FieldValueType.BOOLEAN, FieldValueType.TIMESTAMP,
                                    FieldValueType.LONG);
                    assertThat(view.fields()).last().satisfies(field -> {
                        assertThat(field.valuePresentation()).isEqualTo(FieldValuePresentation.FILE_SIZE);
                        assertThat(field.uiType()).isNull();
                    });
                });
        assertThat(context.uiDescriptor().page().detail().editor())
                .satisfies(view -> {
                    assertThat(view.viewKind()).isEqualTo(ModuleViewKind.FORM);
                    assertThat(view.fields()).extracting(field -> field.fieldRef().fieldId())
                            .containsExactly("field-name", "field-organization");
                    assertThat(view.fields()).last()
                            .satisfies(field -> assertThat(field.reference())
                                    .satisfies(reference -> {
                                        assertThat(reference.targetModuleAlias()).isEqualTo("base.product");
                                        assertThat(reference.cardinality()).isEqualTo(ReferenceCardinality.ONE);
                                    }));
                });
    }

    @Test
    void shouldResolveRecordActionAvailabilityFromActionAuthDataAuthAndBusinessContributor() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        when(moduleService.resolveVisibleModule("iam.user"))
                .thenReturn(module("iam.user", "用户管理", ModuleKind.STATIC));
        when(actionService.listByModuleAliases(List.of("iam.user"))).thenReturn(List.of());
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", "iam.user", "用户管理")
                                                    .parentModuleAlias(null)
                                                    .actions(List.of(
                        StaticModuleActionDefinition.platformAction(PlatformAction.QUERY),
                        StaticModuleActionDefinition.platformAction(PlatformAction.VIEW),
                        StaticModuleActionDefinition.platformAction(PlatformAction.UPDATE),
                        StaticModuleActionDefinition.recordAction("resetPassword", "重置密码"),
                        new StaticModuleActionDefinition(
                                "touchAny",
                                "touchAny",
                                "全级别动作",
                                EntityActionLevel.ANY,
                                EntityActionAccessMode.AUTH_REQUIRED,
                                true,
                                false,
                                null
                        )
                ))
                                                    .build();
        ScopedModuleAbility scopedAbility = mock(ScopedModuleAbility.class);
        when(scopedAbility.getModuleAlias()).thenReturn("iam.user");
        doAnswer(invocation -> {
            ActionExecutionPolicy policy = invocation.getArgument(0);
            if (PlatformAction.UPDATE.matches(policy.actionCode())) {
                throw new PlatformException("denied");
            }
            return null;
        }).when(scopedAbility).requireRecordScope(any(ActionExecutionPolicy.class), any());
        PlatformModuleRuntimeContextService runtimeContextService = new PlatformModuleRuntimeContextService(
                moduleService,
                actionService,
                new StaticModuleDefinitionCatalog(List.of(definition)),
                null,
                null,
                null,
                allowAllPolicy()
        );
        PlatformRecordActionAvailabilityService service = new PlatformRecordActionAvailabilityService(
                runtimeContextService,
                null,
                null,
                List.of(scopedAbility),
                List.of((moduleAlias, actionCode, recordId) -> {
                    if ("iam.user".equals(moduleAlias) && "resetPassword".equals(actionCode)) {
                        return java.util.Optional.of(RecordActionAvailabilityDecision.unavailable(
                                "cannot administrate current user's password"));
                    }
                    return java.util.Optional.empty();
                })
        );

        PlatformRecordActionAvailability availability = service.recordActions("iam.user", "user-1");

        assertThat(availability.recordId()).isEqualTo("user-1");
        assertThat(availability.actions()).extracting(PlatformRecordActionAvailability.Action::actionCode)
                .containsExactly("view", "update", "resetPassword", "touchAny")
                .doesNotContain("query");
        assertThat(availability.actions()).filteredOn(action -> "view".equals(action.actionCode()))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.available()).isTrue();
                    assertThat(action.reason()).isNull();
                });
        assertThat(availability.actions()).filteredOn(action -> "update".equals(action.actionCode()))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.available()).isFalse();
                    assertThat(action.reason()).isEqualTo("no data auth");
                });
        assertThat(availability.actions()).filteredOn(action -> "resetPassword".equals(action.actionCode()))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.available()).isFalse();
                    assertThat(action.reason()).isEqualTo("cannot administrate current user's password");
                });
    }

    @Test
    void shouldResolveDynamicRecordActionAvailabilityThroughUnifiedResolver() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        DynamicRecordService dynamicRecordService = mock(DynamicRecordService.class);
        DynamicRecord record = new DynamicRecord(entity("contract",
                Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE)));
        record.setId("contract-1");
        when(moduleService.resolveVisibleModule("sales.contract"))
                .thenReturn(module("sales.contract", "合同", ModuleKind.DYNAMIC));
        when(actionService.listByModuleAliases(List.of("sales.contract"))).thenReturn(List.of());
        when(dynamicRecordService.describe("sales.contract")).thenReturn(new DynamicModuleDescriptor(
                "sales.contract",
                "合同",
                "contract",
                List.of(
                        dynamicAction("export", EntityActionLevel.LIST),
                        dynamicAction("submit", EntityActionLevel.RECORD),
                        dynamicAction("preview", EntityActionLevel.ANY),
                        dynamicAction("archive", EntityActionLevel.BATCH)
                ),
                List.of(dynamicEntity("contract", "CRUD")),
                List.of(),
                List.of(),
                List.of()
        ));
        when(dynamicRecordService.mainEntityAlias("sales.contract")).thenReturn("contract");
        when(dynamicRecordService.select("sales.contract", "contract", "contract-1")).thenReturn(record);
        when(dynamicRecordService.actions("sales.contract")).thenReturn(List.of(
                dynamicAction("export", EntityActionLevel.LIST),
                dynamicAction("submit", EntityActionLevel.RECORD),
                dynamicAction("preview", EntityActionLevel.ANY),
                dynamicAction("archive", EntityActionLevel.BATCH)
        ));
        when(dynamicRecordService.actionAuthorizationAvailability("sales.contract", "contract", "submit",
                Set.of("contract-1"))).thenReturn(DynamicActionAvailability.available("submit"));
        when(dynamicRecordService.actionAuthorizationAvailability("sales.contract", "contract", "preview",
                Set.of("contract-1"))).thenReturn(DynamicActionAvailability.available("preview"));
        when(dynamicRecordService.actionAvailability("sales.contract", "submit", record))
                .thenReturn(DynamicActionAvailability.unavailable("submit", "只有草稿合同可以提交"));
        when(dynamicRecordService.actionAvailability("sales.contract", "preview", record))
                .thenReturn(DynamicActionAvailability.available("preview"));
        ActiveTenantVerifier activeTenantVerifier = mock(ActiveTenantVerifier.class);
        PlatformModuleRuntimeContextService runtimeContextService = new PlatformModuleRuntimeContextService(
                moduleService,
                actionService,
                new StaticModuleDefinitionCatalog(List.of()),
                dynamicRecordService,
                null,
                null,
                allowAllPolicy()
        );
        PlatformRecordActionAvailabilityService service = new PlatformRecordActionAvailabilityService(
                runtimeContextService,
                dynamicRecordService,
                new net.ximatai.muyun.spring.web.TenantRequestScope(activeTenantVerifier),
                List.of(),
                List.of()
        );

        PlatformRecordActionAvailability availability;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            availability = service.recordActions("sales.contract", "contract-1");
        }

        assertThat(availability.recordId()).isEqualTo("contract-1");
        assertThat(availability.actions()).extracting(PlatformRecordActionAvailability.Action::actionCode)
                .containsExactly("submit", "preview");
        assertThat(availability.actions()).filteredOn(action -> "submit".equals(action.actionCode()))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.available()).isFalse();
                    assertThat(action.reason()).isEqualTo("只有草稿合同可以提交");
                });
        assertThat(availability.actions()).filteredOn(action -> "preview".equals(action.actionCode()))
                .singleElement()
                .satisfies(action -> assertThat(action.available()).isTrue());
        verify(activeTenantVerifier).verifyActiveTenant("tenant-a");
    }

    @Test
    void shouldProjectPlatformManagedProtectionIntoStaticRecordActionAvailability() {
        PlatformModuleService moduleCatalog = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        when(moduleCatalog.resolveVisibleModule("platform.module"))
                .thenReturn(module("platform.module", "模块管理", ModuleKind.STATIC));
        when(actionService.listByModuleAliases(List.of("platform.module"))).thenReturn(List.of());
        StaticModuleDefinition definition = StaticModuleDefinition.builder("platform", "platform.module", "模块管理")
                .parentModuleAlias(null)
                .actions(List.of(
                        StaticModuleActionDefinition.platformAction(PlatformAction.UPDATE),
                        StaticModuleActionDefinition.platformAction(PlatformAction.DELETE),
                        StaticModuleActionDefinition.platformAction(PlatformAction.SORT)))
                .build();
        ManagedModuleAbility managedAbility = mock(ManagedModuleAbility.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        PlatformModule managed = module("platform.module", "模块管理", ModuleKind.STATIC);
        managed.setSystemManaged(Boolean.TRUE);
        when(managedAbility.getModuleAlias()).thenReturn("platform.module");
        org.mockito.Mockito.doReturn(managed).when(managedAbility).select("platform.module");
        PlatformModuleRuntimeContextService runtimeContextService = new PlatformModuleRuntimeContextService(
                moduleCatalog,
                actionService,
                new StaticModuleDefinitionCatalog(List.of(definition)),
                null,
                null,
                null,
                allowAllPolicy());
        PlatformRecordActionAvailabilityService service = new PlatformRecordActionAvailabilityService(
                runtimeContextService, null, null, List.of(managedAbility), List.of());

        PlatformRecordActionAvailability availability = service.recordActions("platform.module", "platform.module");

        assertThat(availability.actions()).filteredOn(action -> "update".equals(action.actionCode()))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.available()).isFalse();
                    assertThat(action.reason()).isEqualTo("平台托管记录不可编辑");
                });
        assertThat(availability.actions()).filteredOn(action -> "delete".equals(action.actionCode()))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.available()).isFalse();
                    assertThat(action.reason()).isEqualTo("平台托管记录不可删除");
                });
        assertThat(availability.actions()).filteredOn(action -> "sort".equals(action.actionCode()))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.available()).isTrue();
                    assertThat(action.reason()).isNull();
                });
    }

    @Test
    void shouldRejectDynamicRecordActionAvailabilityWithoutTenantContext() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        DynamicRecordService dynamicRecordService = mock(DynamicRecordService.class);
        ActiveTenantVerifier activeTenantVerifier = mock(ActiveTenantVerifier.class);
        when(moduleService.resolveVisibleModule("sales.contract"))
                .thenReturn(module("sales.contract", "合同", ModuleKind.DYNAMIC));
        when(actionService.listByModuleAliases(List.of("sales.contract"))).thenReturn(List.of());
        when(dynamicRecordService.describe("sales.contract")).thenReturn(new DynamicModuleDescriptor(
                "sales.contract",
                "合同",
                "contract",
                List.of(dynamicAction("submit", EntityActionLevel.RECORD)),
                List.of(dynamicEntity("contract", "CRUD")),
                List.of(),
                List.of(),
                List.of()
        ));
        PlatformModuleRuntimeContextService runtimeContextService = new PlatformModuleRuntimeContextService(
                moduleService,
                actionService,
                new StaticModuleDefinitionCatalog(List.of()),
                dynamicRecordService,
                null,
                null,
                allowAllPolicy()
        );
        PlatformRecordActionAvailabilityService service = new PlatformRecordActionAvailabilityService(
                runtimeContextService,
                dynamicRecordService,
                new net.ximatai.muyun.spring.web.TenantRequestScope(activeTenantVerifier),
                List.of(),
                List.of()
        );

        TenantContext.clear();

        assertThatThrownBy(() -> service.recordActions("sales.contract", "contract-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessage("sales.contract requires tenant context");
        verify(activeTenantVerifier, never()).verifyActiveTenant(any());
    }

    @Test
    void shouldUseRuntimeContextActionsForDynamicRecordAvailability() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        DynamicRecordService dynamicRecordService = mock(DynamicRecordService.class);
        ActiveTenantVerifier activeTenantVerifier = mock(ActiveTenantVerifier.class);
        DynamicRecord record = new DynamicRecord(entity("contract",
                Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE)));
        record.setId("contract-1");
        PlatformModuleAction disabledSubmit = action("sales.contract", PlatformAction.UPDATE);
        disabledSubmit.setActionCode("submit");
        disabledSubmit.setEnabled(Boolean.FALSE);
        when(moduleService.resolveVisibleModule("sales.contract"))
                .thenReturn(module("sales.contract", "合同", ModuleKind.DYNAMIC));
        when(actionService.listByModuleAliases(List.of("sales.contract"))).thenReturn(List.of(disabledSubmit));
        when(dynamicRecordService.describe("sales.contract")).thenReturn(new DynamicModuleDescriptor(
                "sales.contract",
                "合同",
                "contract",
                List.of(
                        dynamicAction("submit", EntityActionLevel.RECORD),
                        dynamicAction("preview", EntityActionLevel.RECORD)
                ),
                List.of(dynamicEntity("contract", "CRUD")),
                List.of(),
                List.of(),
                List.of()
        ));
        when(dynamicRecordService.actions("sales.contract")).thenReturn(List.of(
                dynamicAction("submit", EntityActionLevel.RECORD),
                dynamicAction("preview", EntityActionLevel.RECORD)
        ));
        when(dynamicRecordService.actionAuthorizationAvailability("sales.contract", "contract", "preview",
                Set.of("contract-1"))).thenReturn(DynamicActionAvailability.available("preview"));
        when(dynamicRecordService.select("sales.contract", "contract", "contract-1")).thenReturn(record);
        when(dynamicRecordService.actionAvailability("sales.contract", "preview", record))
                .thenReturn(DynamicActionAvailability.available("preview"));
        PlatformModuleRuntimeContextService runtimeContextService = new PlatformModuleRuntimeContextService(
                moduleService,
                actionService,
                new StaticModuleDefinitionCatalog(List.of()),
                dynamicRecordService,
                null,
                null,
                allowAllPolicy()
        );
        PlatformRecordActionAvailabilityService service = new PlatformRecordActionAvailabilityService(
                runtimeContextService,
                dynamicRecordService,
                new net.ximatai.muyun.spring.web.TenantRequestScope(activeTenantVerifier),
                List.of(),
                List.of()
        );

        PlatformRecordActionAvailability availability;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            availability = service.recordActions("sales.contract", "contract-1");
        }

        assertThat(availability.actions()).extracting(PlatformRecordActionAvailability.Action::actionCode)
                .containsExactly("preview");
        verify(dynamicRecordService, never()).actionAuthorizationAvailability("sales.contract",
                "contract", "submit", Set.of("contract-1"));
    }

    @Test
    void shouldRejectDynamicRecordActionAvailabilityWhenRecordDoesNotExist() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        DynamicRecordService dynamicRecordService = mock(DynamicRecordService.class);
        ActiveTenantVerifier activeTenantVerifier = mock(ActiveTenantVerifier.class);
        when(moduleService.resolveVisibleModule("sales.contract"))
                .thenReturn(module("sales.contract", "合同", ModuleKind.DYNAMIC));
        when(actionService.listByModuleAliases(List.of("sales.contract"))).thenReturn(List.of());
        when(dynamicRecordService.describe("sales.contract")).thenReturn(new DynamicModuleDescriptor(
                "sales.contract",
                "合同",
                "contract",
                List.of(dynamicAction("submit", EntityActionLevel.RECORD)),
                List.of(dynamicEntity("contract", "CRUD")),
                List.of(),
                List.of(),
                List.of()
        ));
        when(dynamicRecordService.mainEntityAlias("sales.contract")).thenReturn("contract");
        when(dynamicRecordService.actions("sales.contract"))
                .thenReturn(List.of(dynamicAction("submit", EntityActionLevel.RECORD)));
        when(dynamicRecordService.actionAuthorizationAvailability("sales.contract", "contract", "submit",
                Set.of("missing"))).thenReturn(DynamicActionAvailability.available("submit"));
        PlatformModuleRuntimeContextService runtimeContextService = new PlatformModuleRuntimeContextService(
                moduleService,
                actionService,
                new StaticModuleDefinitionCatalog(List.of()),
                dynamicRecordService,
                null,
                null,
                allowAllPolicy()
        );
        PlatformRecordActionAvailabilityService service = new PlatformRecordActionAvailabilityService(
                runtimeContextService,
                dynamicRecordService,
                new net.ximatai.muyun.spring.web.TenantRequestScope(activeTenantVerifier),
                List.of(),
                List.of()
        );

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> service.recordActions("sales.contract", "missing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("dynamic record does not exist: missing");
        }
    }


    private PlatformModule module(String alias, String title, ModuleKind moduleKind) {
        PlatformModule module = new PlatformModule();
        module.setAlias(alias);
        module.setApplicationAlias(alias.substring(0, alias.indexOf('.')));
        module.setTitle(title);
        module.setModuleKind(moduleKind);
        module.setEntryType(ModuleEntryType.ROUTE);
        module.setEntryRoute("/iam/organizations");
        return module;
    }

    private interface ScopedModuleAbility extends CrudAbility<PlatformModule>, DataScopeAbility<PlatformModule> {
    }

    private interface ManagedModuleAbility extends PlatformManagedProtectionAbility<PlatformModule> {
    }

    private EntityDefinition entity(String alias, Set<EntityCapability> capabilities) {
        return new EntityDefinition(alias, alias, alias, List.of(FieldDefinition.titleField()), capabilities);
    }

    private PlatformModuleAction action(String moduleAlias, PlatformAction platformAction) {
        PlatformModuleAction action = new PlatformModuleAction();
        action.setModuleAlias(moduleAlias);
        action.setActionCode(platformAction.code());
        action.setPermissionActionCode(platformAction.permissionActionCode());
        action.setTitle(platformAction.title());
        action.setCategory(EntityActionCategory.STANDARD);
        action.setActionLevel(EntityActionLevel.valueOf(platformAction.level().name()));
        action.setAccessMode(EntityActionAccessMode.valueOf(platformAction.accessMode().name()));
        action.setActionAuth(platformAction.actionAuth());
        action.setDataAuth(platformAction.dataAuth());
        action.setDefaultGrantPolicy(platformAction.defaultGrantPolicy());
        action.setEnabled(Boolean.TRUE);
        return action;
    }

    private DynamicActionDescriptor dynamicAction(PlatformAction platformAction) {
        return new DynamicActionDescriptor(
                platformAction.code(),
                platformAction.title(),
                true,
                EntityActionLevel.valueOf(platformAction.level().name()),
                EntityActionCategory.STANDARD,
                EntityActionAccessMode.valueOf(platformAction.accessMode().name()),
                platformAction.actionAuth(),
                platformAction.dataAuth(),
                platformAction.defaultGrantPolicy(),
                platformAction.inheritActionCode(),
                false,
                null,
                EntityActionExecutorType.STANDARD,
                null
        );
    }

    private DynamicActionDescriptor dynamicAction(String code, EntityActionLevel level) {
        return new DynamicActionDescriptor(
                code,
                code,
                true,
                level,
                EntityActionCategory.CUSTOM,
                EntityActionAccessMode.AUTH_REQUIRED,
                true,
                true,
                null,
                null,
                false,
                null,
                EntityActionExecutorType.SERVICE,
                null
        );
    }

    private DynamicEntityDescriptor dynamicEntity(String entityAlias, String... capabilities) {
        return dynamicEntity(entityAlias, List.of(), capabilities);
    }

    private DynamicEntityDescriptor dynamicEntity(String entityAlias,
                                                  List<DynamicFieldDescriptor> fields,
                                                  String... capabilities) {
        return new DynamicEntityDescriptor(
                entityAlias,
                entityAlias,
                Set.of(capabilities),
                fields,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private DynamicReferenceDescriptor dynamicReference(String sourceEntityAlias, String sourceField) {
        return new DynamicReferenceDescriptor(
                sourceEntityAlias,
                sourceField,
                "base.product",
                "product",
                ReferenceCardinality.ONE,
                List.of()
        );
    }

    private PlatformUiSet uiSet(String id, String moduleAlias, String alias, PlatformUiSetType type) {
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId(id);
        uiSet.setModuleAlias(moduleAlias);
        uiSet.setAlias(alias);
        uiSet.setTitle(alias);
        uiSet.setSetType(type);
        uiSet.setEnabled(Boolean.TRUE);
        return uiSet;
    }

    private PlatformUiConfig uiConfig(String id, String uiSetId, String title) {
        PlatformUiConfig config = new PlatformUiConfig();
        config.setId(id);
        config.setUiSetId(uiSetId);
        config.setTitle(title);
        config.setLayoutJson("{\"template\":\"LIST_DETAIL_CARD\",\"traits\":[]}");
        config.setClientType(PlatformUiClientType.WEB);
        config.setPublished(Boolean.TRUE);
        config.setEnabled(Boolean.TRUE);
        return config;
    }

    private PlatformResolvedUiField resolvedField(String uiConfigId,
                                                  String fieldId,
                                                  String relationAlias,
                                                  String fieldName,
                                                  String title,
                                                  String width,
                                                  String align) {
        return resolvedField(uiConfigId, fieldId, relationAlias, fieldName, title, width, align, null);
    }

    private PlatformResolvedUiField resolvedField(String uiConfigId,
                                                  String fieldId,
                                                  String relationAlias,
                                                  String fieldName,
                                                  String title,
                                                  String width,
                                                  String align,
                                                  String uiControlAlias) {
        return new PlatformResolvedUiField(
                uiConfigId,
                fieldId,
                relationAlias,
                "customer",
                fieldName,
                fieldName,
                title,
                "string",
                "STORED",
                uiControlAlias,
                true,
                false,
                false,
                null,
                null,
                width == null ? null : Integer.valueOf(width),
                align,
                null
        );
    }

    private ActionExecutionPolicyService allowAllPolicy() {
        return context -> {
        };
    }
}
