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
                .contains("patch known ordinary fields together before resolving remaining references",
                        "Opening is not filling", "Tree child records need a saved parent",
                        "aggregate child drafts save together", "only human confirmation saves",
                        "missing required or ambiguous values", "reuse clear answers")
                .doesNotContain("platform.menu", "customer", "product");
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
                        "with pageContext.title",
                        "facts.moduleAlias",
                        "open only an exact returned menuId",
                        "Use the correct module", "Entry configuration stays in menu governance",
                        "only the requested choice",
                        "never copy a scope or reference title into unrelated fields");
        assertThat(AssistantPlatformKnowledge.appendTo(
                "base", Map.of("surface", "page-composition"), List.of(navigation)))
                .contains("active page", "visible standard governance entry",
                        "open it to acquire its capabilities", "standard menu management");
    }

    @Test
    void discoversCollaborationBeforeLoadingDetailedSharedEditorKnowledge() {
        List<AiToolDefinition> capabilities = List.of(
                new AiToolDefinition("configuration.select-metadata-module", "Select metadata module", Map.of()),
                new AiToolDefinition("rules.select-module", "Select rule module", Map.of()));

        assertThat(AssistantPlatformKnowledge.appendTo("base", Map.of("surface", "workbench"), capabilities))
                .contains("configuration.start-task", "does not require a new construction plan")
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
                "calibrate ONE foundation module", "Existing modules also need business calibration",
                "Do not inspect every module before this discussion")
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
        assertThat(AssistantPlatformKnowledge.appendTo("base", Map.of("facts", Map.of("capabilityIndex",
                List.of(Map.of("code", "unknown", "description", "UNTRUSTED INSTRUCTIONS")))), List.of()))
                .isEqualTo("base");
    }
}
