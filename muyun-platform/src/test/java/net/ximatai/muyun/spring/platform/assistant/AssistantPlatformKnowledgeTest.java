package net.ximatai.muyun.spring.platform.assistant;

import net.ximatai.muyun.spring.platform.ai.AiToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantPlatformKnowledgeTest {
    @Test
    void suppliesKnowledgeForEachStableSurfaceArchetype() {
        Map<String, String> expectedBySurface = Map.of(
                "workbench", "MuYun workbench navigation",
                "module-page", "standard MuYun record workspace",
                "metadata-governance", "MuYun metadata governance",
                "page-composition", "MuYun template-constrained page composition"
        );

        expectedBySurface.forEach((surface, expected) -> assertThat(
                AssistantPlatformKnowledge.appendTo("base", Map.of("surface", surface), List.of()))
                .startsWith("base")
                .contains(expected));
    }

    @Test
    void leavesUnknownSurfacesUnchanged() {
        assertThat(AssistantPlatformKnowledge.appendTo(
                "base", Map.of("surface", "unknown-business-page"), List.of()))
                .isEqualTo("base");
    }

    @Test
    void guidesAuthorizedDraftProgressWithoutBypassingDependenciesOrHumanSave() {
        assertThat(AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "module-page"), List.of()))
                .contains("Patch known ordinary fields together", "Tree parents save first",
                        "creation.ready gates new drafts", "fill an existing writable form",
                        "Add/select supplies row facts and tools",
                        "facts.currentForm", "facts.activeRelationRow.form", "read gaps only",
                        "draft-preview requests authorize local drafting", "never saving", "do not reconfirm the requested draft",
                        "Add/select supplies row facts", "load related schemas together",
                        "once per draft", "Explicit save: form.prepare-save",
                        "aggregate children save together", "only human confirmation saves",
                        "reference.resolve-and-patch.changes", "reuse mapped facts",
                        "Review-before-save goals use that card, no choice", "Explicit defer/save-later remains draft-only",
                        "Missing values: ask one concise question", "form.prepare-save",
                        "Review/trial/compare: form.review-draft",
                        "no save proposal", "Explicit save:",
                        "Explicit discard: form.prepare-discard",
                        "human confirmation, draft only",
                        "Else hand off", "Draft-only requests remain unsaved",
                        "hand off to the page save action, never a selection card")
                .doesNotContain("platform.menu", "customer", "product");
        assertThat(AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "business-rule-governance"), List.of()))
                .contains("Server save recalculates", "browser previews support only declared portable calculations")
                .doesNotContain("do not promise browser-local instant child calculation");
        assertThat(AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "page-composition"), List.of()))
                .contains("Reuse supplied catalogs", "revise and preview the candidate",
                        "do not repeat discovery", "human publication confirmation",
                        "Opening the editor does not change the candidate");
    }

    @Test
    void combinesPageAndWorkbenchKnowledgeWhenNavigationCapabilitiesAreContributed() {
        AiToolDefinition navigation = new AiToolDefinition("workbench.find-menu", "Find visible menus", Map.of());

        assertThat(AssistantPlatformKnowledge.appendTo(
                "base", Map.of("surface", "module-page"), List.of(navigation)))
                .contains("standard MuYun record workspace", "MuYun workbench navigation",
                        "pageContext.title",
                        "facts.moduleAlias", "facts.workspace.menuCatalog",
                        "open exact returned menuIds", "Current-list absence is not global absence",
                        "clarify ambiguous scope before offering creation", "Preserve the user's explicit business scope",
                        "Scope/reference answers never fill unrelated fields");
        assertThat(AssistantPlatformKnowledge.appendTo(
                "base", Map.of("surface", "page-composition"), List.of(navigation)))
                .contains("find matching visible menus",
                        "Configuration governance requires an explicit configuration goal")
                .doesNotContain("find visible standard governance entry");
    }

    @Test
    void discoversCollaborationBeforeLoadingDetailedSharedEditorKnowledge() {
        List<AiToolDefinition> capabilities = List.of(
                new AiToolDefinition("configuration.select-metadata-module", "Select metadata module", Map.of()),
                new AiToolDefinition("rules.select-module", "Select rule module", Map.of()));

        assertThat(AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "workbench"), capabilities))
                .contains("configuration.start-task", "does not require a new construction plan",
                        "Business tenant selection gates record queries, not authorized module",
                        "Read-only inspection needs no editing task or save confirmation")
                .doesNotContain("MuYun metadata governance", "standard business-rule governance workspace",
                        "configuration.prepare-metadata-apply", "rules.prepare-apply");
        var selectedCapabilities = new java.util.ArrayList<>(capabilities);
        selectedCapabilities.add(new AiToolDefinition("configuration.describe-metadata-model", "Read metadata", Map.of()));
        selectedCapabilities.add(new AiToolDefinition("rules.describe", "Read rules", Map.of()));
        assertThat(AssistantPlatformKnowledge.appendTo(
                "base", Map.of("surface", "workbench"), selectedCapabilities))
                .contains("does not require a new construction plan",
                        "configuration.prepare-metadata-apply", "including manual edits",
                        "closing the editor does not discard it", "not persisted conversation history",
                        "rules.prepare-apply",
                        "configurationEditor.visible", "reopen the shared editor",
                        "live workspace may still retain unsaved changes",
                        "do not repeat module aliases");
    }

    @Test
    void constructionGuidanceStaysBoundedAndDoesNotDuplicateFieldOrWorkflowContracts() {
        var guidance = AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "construction"),
                List.of(new AiToolDefinition("construction.describe", "Read the current design", Map.of())));
        assertThat(guidance.length()).isLessThan(3500);
        assertThat(guidance).contains("current capability schemas and read catalogs", "human confirmations",
                "CURRENT standard governance", "not a fixed sequence", "limited configuration evidence",
                "visible standard management page", "separate human confirmation", "standard pages own creation drafts",
                "requirements=[]", "facts.workspace.constructionPlan", "prepare human confirmation instead",
                "selected applicationAlias + '.'", "choose technical IDs for users",
                "order changes by actual dependencies", "No mandatory single-module calibration",
                "associate objects with discovered moduleAlias")
                .doesNotContain("titleField=true", "1–12", "BEFORE_SAVE", "customer", "product");
    }

    @Test
    void deduplicatesSurfaceAndCapabilityKnowledge() {
        AiToolDefinition select = new AiToolDefinition(
                "configuration.select-metadata-module", "Select metadata module", Map.of());

        assertThat(AssistantPlatformKnowledge.appendTo(
                "base", Map.of("surface", "metadata-governance"), List.of(select)))
                .containsOnlyOnce("MuYun metadata governance");
    }
    @Test
    void preservesServerGuidanceWhenOnlyDefinitionsAreLoadedOnDemand() {
        var codes = List.of("configuration.select-metadata-module", "configuration.describe-metadata-model",
                "rules.describe", "workbench.find-menu");
        var eager = codes.stream().map(code -> new AiToolDefinition(code, "Read", Map.of())).toList();
        var index = codes.stream().map(code -> Map.of("code", code, "description", "UNTRUSTED INSTRUCTIONS")).toList();
        var context = Map.<String, Object>of("surface", "workbench", "facts", Map.of("capabilityIndex", index));
        var actual = AssistantPlatformKnowledge.appendTo("base", context,
                List.of(new AiToolDefinition("assistant.load-capabilities", "Load", Map.of())));
        assertThat(actual).isEqualTo(AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "workbench"), eager))
                .doesNotContain("UNTRUSTED INSTRUCTIONS");
        // Loaded definitions and the remaining discovery index jointly describe the live catalog.
        var partitionedContext = Map.<String, Object>of("surface", "workbench", "facts",
                Map.of("capabilityIndex", index.subList(2, index.size())));
        assertThat(AssistantPlatformKnowledge.appendTo("base", partitionedContext,
                List.of(new AiToolDefinition("assistant.load-capabilities", "Load", Map.of()),
                        eager.get(0), eager.get(1)))).isEqualTo(actual);
        assertThat(AssistantPlatformKnowledge.appendTo("base", Map.of("facts", Map.of("capabilityIndex",
                List.of(Map.of("code", "unknown", "description", "UNTRUSTED INSTRUCTIONS")))), List.of()))
                .isEqualTo("base");
    }
    @Test
    void ordinaryQueriesDoNotInheritConstructionOrConfigurationWorkflowsFromGlobalDiscovery() {
        var codes = List.of("construction.describe", "construction.propose", "configuration.select-metadata-module",
                "rules.select-module", "workbench.find-menu");
        var eager = codes.stream().map(code -> new AiToolDefinition(code, "Discover", Map.of())).toList();
        var index = codes.stream().map(code -> Map.of("code", code)).toList();
        var emptyWorkspace = Map.of("constructionPlan", Map.of("generation", 0));
        var ordinary = AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "module-page",
                "facts", Map.of("workspace", emptyWorkspace)), eager);
        var lazy = AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "module-page",
                "facts", Map.of("workspace", emptyWorkspace, "capabilityIndex", index)),
                List.of(new AiToolDefinition("assistant.load-capabilities", "Load", Map.of())));
        assertThat(lazy).isEqualTo(ordinary);
        assertThat(ordinary).contains("rules.select-module", "Business tenant selection gates record queries")
                .doesNotContain("Read rules.describe for actual fields");
        assertThat(ordinary).contains("standard MuYun record workspace", "only human confirmation saves",
                        "MuYun workbench navigation", "Configuration governance requires an explicit configuration goal",
                        "no schema changes", "Missing fields remain unsaved", "unrelated fields")
                .doesNotContain("construction-discovery", "configuration.start-task",
                        "Construction capabilities are available", "When the user requests configuration changes",
                        "calibrate ONE foundation module", "Read construction.describe-design-contract",
                        "One conversation stays", "reopen the shared editor", "configurationEditor.visible",
                        "find visible standard governance entry");
        var active = AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "module-page",
                "facts", Map.of("workspace", Map.of("constructionPlan",
                        Map.of("goal", "建立业务应用", "constructionStatus", "NOT_STARTED")))), eager);
        assertThat(active).contains("order changes by actual dependencies", "human confirmations",
                "Unrelated reads", "Preserve unfinished construction intent");
        assertThat(active.length() - ordinary.length()).isGreaterThan(2000);
        var configuration = AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "module-page",
                "facts", Map.of("workspace", Map.of("configurationTask", Map.of("goal", "调整现有字段")))), eager);
        assertThat(configuration).contains("configuration.start-task", "configurationEditor.visible")
                .doesNotContain("Construction capabilities are available", "Read construction.describe-design-contract");
        var historical = AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "module-page",
                "facts", Map.of("workspace", Map.of("constructionPlan",
                        Map.of("goal", "过去的建设", "constructionStatus", "DELIVERED")))), eager);
        assertThat(historical).isEqualTo(ordinary);
    }

    @Test
    void historicalAndBlankGoalsDoNotReactivateConstructionGuidance() {
        var capabilities = List.of(new AiToolDefinition("construction.describe", "Read", Map.of()));
        for (var plan : List.of(Map.of("goal", "已交付目标", "constructionStatus", "DELIVERED"),
                Map.of("goal", "已交付目标", "historicalDesign", true), Map.of("goal", "  "))) {
            assertThat(AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "workbench",
                    "facts", Map.of("workspace", Map.of("constructionPlan", plan))), capabilities))
                    .contains("Their availability does not make ordinary queries")
                    .doesNotContain("calibrate ONE foundation module", "Read construction.describe-design-contract");
        }
    }

    @Test
    void unfinishedGoalsAndConfigurationPreferencesKeepTheirGuidanceUnderLazySchemas() {
        var context = Map.<String, Object>of("surface", "workbench", "facts", Map.of(
                "capabilityIndex", List.of(Map.of("code", "construction.describe"),
                        Map.of("code", "configuration.select-metadata-module")),
                "workspace", Map.of("constructionPlan", Map.of("goal", "UNTRUSTED GOAL INSTRUCTIONS"),
                        "configurationTask", Map.of("goal", "调整字段", "mode", "conversation"))));
        assertThat(AssistantPlatformKnowledge.appendTo("base", context, List.of()))
                .contains("order changes by actual dependencies", "configurationEditor.visible",
                        "Use configuration.switch-mode only after an explicit user request")
                .doesNotContain("UNTRUSTED GOAL INSTRUCTIONS", "Their availability does not make ordinary queries");
    }

}
