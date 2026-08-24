package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.ExternalQueryValueSource;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryField;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.database.core.orm.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;

class ModuleExecutionPlanCatalogTest {
    @Test
    void shouldValidatePersistentQueryControlsAgainstSourceNeutralQuerySchema() {
        String alias = "iam.user";
        ResolvedModuleUiDescriptor uiDescriptor = persistentQueryUi(alias);
        ResolvedModuleReadModel readModel = new ResolvedModuleReadModel(alias, "user", List.of());
        QueryDescriptor descriptor = QueryDescriptor.builder(alias)
                .externalCriteria("onlineOnly", QueryValueType.BOOLEAN, ExternalQueryValueSource.USER_INPUT,
                        value -> null)
                .build();

        assertThat(new ModuleExecutionPlan(alias, "static-1", uiDescriptor, readModel, List.of(), descriptor,
                QuerySchema.from(descriptor), List.of(), List.of(), false).querySchema().externalCriteria())
                .containsExactly(new QuerySchema.ExternalCriteria("onlineOnly", "BOOLEAN", "USER_INPUT"));

        QuerySchema dynamicSchema = new QuerySchema(alias, "user", null, List.of(),
                List.of(new QuerySchema.ExternalCriteria("onlineOnly", "BOOLEAN", "USER_INPUT")), List.of());
        assertThatCode(() -> new ModuleExecutionPlan(alias, "dynamic-1", uiDescriptor, readModel, List.of(),
                QueryDescriptor.builder(alias).build(), dynamicSchema, List.of(), List.of(), false))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectPersistentQueryControlThatCouldOverridePageContext() {
        String alias = "iam.user";
        QueryDescriptor descriptor = QueryDescriptor.builder(alias)
                .externalCriteria("onlineOnly", QueryValueType.BOOLEAN, ExternalQueryValueSource.USER_INPUT,
                        value -> null)
                .build();

        assertThatThrownBy(() -> new ModuleExecutionPlan(alias, "static-1", persistentQueryUi(alias),
                new ResolvedModuleReadModel(alias, "user", List.of()),
                List.of(PageContextBindingDefinition.navigator("tenant", PageContextTarget.LIST_QUERY, "onlineOnly")),
                descriptor, QuerySchema.from(descriptor), List.of(), List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not override page context criteria");
    }
    @Test
    void shouldCompileStaticDefinitionOnceIntoCachedExecutionFacts() {
        ModuleExecutionPlanCatalog catalog = new ModuleExecutionPlanCatalog(
                new StaticModuleDefinitionCatalog(List.of(module("iam.user", "username"))));

        ModuleExecutionPlan first = catalog.find("iam.user").orElseThrow();
        ModuleExecutionPlan second = catalog.find("iam.user").orElseThrow();

        assertThat(second).isSameAs(first);
        assertThat(first.versionKey()).startsWith("static-");
        assertThat(first.uiDescriptor().moduleAlias()).isEqualTo("iam.user");
        assertThat(first.readModel().moduleAlias()).isEqualTo("iam.user");
        assertThat(first.pageContextBindings()).isEmpty();
        assertThat(first.querySchema().fields()).extracting("name").containsExactly("username");
        assertThat(first.queryDescriptor().defaultSorts()).extracting(Sort::getField).containsExactly("username");
        assertThat(first.actions()).extracting(StaticModuleActionDefinition::actionCode).containsExactly("view");
        assertThat(first.dataScopeEnabled()).isTrue();
    }

    @Test
    void shouldFailWhenStaticDeclarationCannotProduceExecutionFacts() {
        ModuleExecutionPlanCatalog catalog = new ModuleExecutionPlanCatalog(
                new StaticModuleDefinitionCatalog(List.of(module("iam.user", "unknownField"))));

        assertThatThrownBy(catalog::plans)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknownField");
    }

    @Test
    void shouldValidateStaticSummaryContributorDuringPlanCompilation() {
        StaticModuleDefinition definition = summaryModule("iam.user", "onlineUsers", "iam.active-user-count");

        ModuleExecutionPlanCatalog knownContributorCatalog = new ModuleExecutionPlanCatalog(
                new StaticModuleDefinitionCatalog(List.of(definition)),
                new ListQuerySummaryContributorCatalog(List.of(summaryContributor("iam.user", "iam.active-user-count"))));
        assertThatCode(knownContributorCatalog::plans).doesNotThrowAnyException();

        ModuleExecutionPlanCatalog missingContributorCatalog = new ModuleExecutionPlanCatalog(
                new StaticModuleDefinitionCatalog(List.of(definition)),
                new ListQuerySummaryContributorCatalog(List.of()));
        assertThatThrownBy(missingContributorCatalog::plans)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no list query summary contributor: iam.user.iam.active-user-count");
    }

    @Test
    void shouldRejectInvalidDynamicSummaryCandidateBeforeItIsInstalled() {
        String moduleAlias = "iam.customer";
        ResolvedModuleUiDescriptor descriptor = summaryUi(moduleAlias, "activeUsers", "iam.active-user-count");
        ModuleExecutionPlan candidate = new ModuleExecutionPlan(moduleAlias, "dynamic-runtime-1-ui-1", descriptor,
                new ResolvedModuleReadModel(moduleAlias, "customer", List.of()), List.of());
        ModuleExecutionPlanCatalog catalog = new ModuleExecutionPlanCatalog(new StaticModuleDefinitionCatalog(List.of()),
                new ListQuerySummaryContributorCatalog(List.of()));

        assertThatThrownBy(() -> catalog.validateCandidate(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no list query summary contributor: iam.customer.iam.active-user-count");
        assertThat(catalog.find(moduleAlias)).isEmpty();
    }

    @Test
    void shouldCompileCompleteChildWireFactsIndependentlyFromRelationEditorProjection() {
        String moduleAlias = "test.parent";
        StaticModuleDefinition definition = StaticModuleDefinition.builder("test", moduleAlias, "Parent")
                .actions(List.of(
                        relationAction("child_query", "child_view", true),
                        relationAction("child_create", "child_create", false),
                        relationAction("child_update", "child_update", true),
                        relationAction("child_delete", "child_delete", true)))
                .entities(List.of(
                        new EntityDefinition("parent", "test_parent", "Parent",
                                List.of(FieldDefinition.string("title", "Title"))),
                        new EntityDefinition("child", "test_child", "Child", List.of(
                                FieldDefinition.string("parentId", "Parent id"),
                                FieldDefinition.string("title", "Title"),
                                FieldDefinition.longInteger("hiddenLong", "Hidden long"),
                                FieldDefinition.decimal("hiddenDecimal", "Hidden decimal")))))
                .uiDefinition(ModuleUiDefinition.builder(moduleAlias)
                        .editorContribution("child", form -> form.field("child", "title", field -> { }))
                        .managedDetailRelation("children", "Children", "child", "parentId",
                                PageDetailRelationMutationDefinition.standardCrud())
                        .build())
                .build();

        ModuleExecutionPlan plan = new ModuleExecutionPlanCatalog(
                new StaticModuleDefinitionCatalog(List.of(definition))).find(moduleAlias).orElseThrow();

        assertThat(plan.detailRelationWireFieldTypes().get("children"))
                .containsEntry("hiddenLong", FieldValueType.LONG)
                .containsEntry("hiddenDecimal", FieldValueType.DECIMAL)
                .containsEntry("title", FieldValueType.STRING);
        assertThat(plan.uiDescriptor().editorContributions().getFirst().editor().fields())
                .extracting(field -> field.fieldRef().fieldName())
                .containsExactly("title");
    }

    @Test
    void shouldAtomicallyReplaceDynamicPlanWhenRuntimeOrPublishedUiRevisionChanges() {
        StaticModuleDefinition definition = module("iam.customer", "username");
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(definition);
        AtomicReference<ModuleExecutionPlan> candidate = new AtomicReference<>(new ModuleExecutionPlan(
                "iam.customer", "dynamic-runtime-1-ui-1", compilation.uiDescriptor(), compilation.readModel(), List.of()));
        ModuleExecutionPlanCatalog catalog = new ModuleExecutionPlanCatalog(
                new StaticModuleDefinitionCatalog(List.of()), alias -> Optional.of(candidate.get()));

        assertThat(catalog.find("iam.customer")).isEmpty();
        catalog.rebuildDynamicPlan("iam.customer");
        ModuleExecutionPlan first = catalog.find("iam.customer").orElseThrow();
        assertThat(catalog.find("iam.customer").orElseThrow()).isSameAs(first);

        candidate.set(new ModuleExecutionPlan("iam.customer", "dynamic-runtime-2-ui-1",
                compilation.uiDescriptor(), compilation.readModel(), List.of()));
        catalog.onRuntimeEvent(event("iam.customer", RuntimeEventType.MODULE_REFRESHED));
        ModuleExecutionPlan refreshedRuntime = catalog.find("iam.customer").orElseThrow();
        assertThat(refreshedRuntime).isNotSameAs(first);
        assertThat(refreshedRuntime.versionKey()).isEqualTo("dynamic-runtime-2-ui-1");

        candidate.set(new ModuleExecutionPlan("iam.customer", "dynamic-runtime-2-ui-2",
                compilation.uiDescriptor(), compilation.readModel(), List.of()));
        catalog.onRuntimeEvent(event("iam.customer", RuntimeEventType.MODULE_PAGE_CONFIG_PUBLISHED));
        assertThat(catalog.find("iam.customer").orElseThrow().versionKey())
                .isEqualTo("dynamic-runtime-2-ui-1");
        // The publication coordinator already holds the candidate prepared in its transaction;
        // the event must not issue a second, potentially stale resolver read.
        catalog.replaceDynamicPlan("iam.customer", Optional.of(candidate.get()));
        assertThat(catalog.find("iam.customer").orElseThrow().versionKey())
                .isEqualTo("dynamic-runtime-2-ui-2");
    }

    @Test
    void shouldKeepLastKnownGoodDynamicPlanWhenRefreshCompilationFails() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(module("iam.customer", "username"));
        AtomicReference<ModuleExecutionPlan> candidate = new AtomicReference<>(new ModuleExecutionPlan(
                "iam.customer", "dynamic-runtime-1-ui-1", compilation.uiDescriptor(), compilation.readModel(), List.of()));
        DynamicModuleExecutionPlanResolver resolver = new DynamicModuleExecutionPlanResolver() {
            @Override
            public Optional<ModuleExecutionPlan> resolve(String alias) {
                if (candidate.get() == null) {
                    throw new IllegalArgumentException("invalid published page");
                }
                return Optional.of(candidate.get());
            }
        };
        ModuleExecutionPlanCatalog catalog = new ModuleExecutionPlanCatalog(
                new StaticModuleDefinitionCatalog(List.of()), resolver);
        catalog.rebuildDynamicPlan("iam.customer");
        ModuleExecutionPlan knownGood = catalog.find("iam.customer").orElseThrow();

        candidate.set(null);
        catalog.onRuntimeEvent(event("iam.customer", RuntimeEventType.MODULE_PAGE_CONFIG_PUBLISHED));
        assertThat(catalog.find("iam.customer")).containsSame(knownGood);
    }

    @Test
    void shouldInstallPublishedDynamicPlansDuringColdStart() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(module("iam.customer", "username"));
        ModuleExecutionPlan plan = new ModuleExecutionPlan("iam.customer", "dynamic-runtime-1-ui-1",
                compilation.uiDescriptor(), compilation.readModel(), List.of());
        ModuleExecutionPlanCatalog catalog = new ModuleExecutionPlanCatalog(new StaticModuleDefinitionCatalog(List.of()),
                new DynamicModuleExecutionPlanResolver() {
                    @Override
                    public Optional<ModuleExecutionPlan> resolve(String alias) {
                        return Optional.of(plan);
                    }

                    @Override
                    public List<String> moduleAliases() {
                        return List.of("iam.customer");
                    }
                });

        // Dynamic metadata tables are populated after singleton wiring; the published plans
        // therefore become available at the delivery runtime's application-ready phase.
        catalog.installInitialDynamicPlans();

        assertThat(catalog.find("iam.customer")).containsSame(plan);
    }

    @Test
    void shouldMarkSuccessfullyPublishedIncompletePageAsNonExecutable() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(module("iam.customer", "username"));
        AtomicReference<Optional<ModuleExecutionPlan>> candidate = new AtomicReference<>(Optional.of(new ModuleExecutionPlan(
                "iam.customer", "dynamic-runtime-1-ui-1", compilation.uiDescriptor(), compilation.readModel(), List.of())));
        ModuleExecutionPlanCatalog catalog = new ModuleExecutionPlanCatalog(new StaticModuleDefinitionCatalog(List.of()),
                alias -> candidate.get());
        catalog.rebuildDynamicPlan("iam.customer");
        assertThat(catalog.find("iam.customer")).isPresent();

        candidate.set(Optional.empty());
        catalog.rebuildDynamicPlan("iam.customer");

        assertThat(catalog.find("iam.customer")).isEmpty();
    }

    private static RuntimeEvent event(String moduleAlias, RuntimeEventType type) {
        return RuntimeEvent.of(type, moduleAlias, null, null, null, null, true, "test",
                RuntimeMutationSource.SYSTEM, java.util.Map.of());
    }

    private static ResolvedModuleUiDescriptor persistentQueryUi(String moduleAlias) {
        return ModuleUiDescriptorCompiler.compile(ModuleUiDefinition.builder(moduleAlias)
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("username", field -> { }))
                                .persistentQueries(queries -> queries.control("onlineOnly", control -> control
                                        .label("仅在线").uiType(ViewControlType.SWITCH).defaultValue(false))))
                        .detail(detail -> detail.editor(editor -> editor.field("username", field -> { })))))
                .build());
    }

    private static ResolvedModuleUiDescriptor summaryUi(String moduleAlias, String summaryKey, String contributorKey) {
        return ModuleUiDescriptorCompiler.compile(summaryUiDefinition(moduleAlias, summaryKey, contributorKey));
    }

    private static ModuleUiDefinition summaryUiDefinition(String moduleAlias, String summaryKey, String contributorKey) {
        return ModuleUiDefinition.builder(moduleAlias)
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("username", field -> { }))
                                .querySummaries(summaries -> summaries.item(summaryKey, summary -> summary
                                        .label("在线").contributor(contributorKey))))
                        .detail(detail -> detail.editor(editor -> editor.field("username", field -> { })))))
                .build();
    }

    private static StaticModuleDefinition summaryModule(String alias, String summaryKey, String contributorKey) {
        return StaticModuleDefinition.builder("iam", alias, "用户")
                .entry(ModuleEntryType.ROUTE, "/users", null)
                .capabilities(Set.of(EntityCapability.CRUD, EntityCapability.DATA_SCOPE))
                .actions(List.of(StaticModuleActionDefinition.recordAction("view", "查看")))
                .queryDescriptor(QueryDescriptor.builder(alias)
                        .field(QueryField.of("username", QueryValueType.STRING, QueryOperator.EQ))
                        .defaultSort(Sort.asc("username"))
                        .build())
                .entities(List.of(new EntityDefinition("user", "iam_user", "User",
                        List.of(FieldDefinition.string("username", "用户名").column("username")))))
                .uiDefinition(summaryUiDefinition(alias, summaryKey, contributorKey))
                .build();
    }

    private static ListQuerySummaryContributor summaryContributor(String moduleAlias, String contributorKey) {
        return new ListQuerySummaryContributor() {
            @Override public String moduleAlias() { return moduleAlias; }
            @Override public String contributorKey() { return contributorKey; }
            @Override public net.ximatai.muyun.spring.web.WebListQuerySummaryItem summarize(ListQuerySummaryContext context) {
                return new net.ximatai.muyun.spring.web.WebListQuerySummaryItem(context.summaryKey(), 0);
            }
        };
    }

    private static StaticModuleDefinition module(String alias, String field) {
        return StaticModuleDefinition.builder("iam", alias, "用户")
                .entry(ModuleEntryType.ROUTE, "/users", null)
                .capabilities(Set.of(EntityCapability.CRUD, EntityCapability.DATA_SCOPE))
                .actions(List.of(StaticModuleActionDefinition.recordAction("view", "查看")))
                .queryDescriptor(QueryDescriptor.builder(alias)
                        .field(QueryField.of("username", QueryValueType.STRING, QueryOperator.EQ))
                        .defaultSort(Sort.asc("username"))
                        .build())
                .entities(List.of(new EntityDefinition("user", "iam_user", "User",
                        List.of(FieldDefinition.string("username", "用户名").column("username")))))
                .uiDefinition(TestModulePages.listDetail(alias, list -> list.field(field)))
                .build();
    }

    private static StaticModuleActionDefinition relationAction(String actionCode, String permissionActionCode,
                                                               boolean dataAuth) {
        return new StaticModuleActionDefinition(actionCode, permissionActionCode, actionCode,
                dataAuth ? EntityActionLevel.RECORD : EntityActionLevel.LIST,
                EntityActionAccessMode.AUTH_REQUIRED, true, dataAuth, ActionDefaultGrantPolicy.NONE);
    }
}
