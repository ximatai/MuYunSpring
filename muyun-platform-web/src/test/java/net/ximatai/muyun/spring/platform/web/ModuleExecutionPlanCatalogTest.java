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
import net.ximatai.muyun.spring.ability.query.QueryField;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.database.core.orm.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;

class ModuleExecutionPlanCatalogTest {
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
