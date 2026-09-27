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
    void combinesPageAndWorkbenchKnowledgeWhenNavigationCapabilitiesAreContributed() {
        AiToolDefinition navigation = new AiToolDefinition("workbench.find-menu", "Find visible menus", Map.of());

        assertThat(AssistantPlatformKnowledge.appendTo(
                "base", Map.of("surface", "module-page"), List.of(navigation)))
                .contains("standard MuYun record workspace", "MuYun workbench navigation",
                        "with pageContext.title",
                        "facts.moduleAlias",
                        "find visible menus, open only an exact returned menuId",
                        "Do not operate on a related but different module",
                        "only the requested choice",
                        "never copy a scope or reference title into unrelated fields");
    }

    @Test
    void suppliesSharedEditorKnowledgeFromCapabilitiesWithoutAnEditorPage() {
        List<AiToolDefinition> capabilities = List.of(
                new AiToolDefinition("configuration.select-metadata-module", "Select metadata module", Map.of()),
                new AiToolDefinition("rules.select-module", "Select rule module", Map.of()));

        assertThat(AssistantPlatformKnowledge.appendTo(
                "base", Map.of("surface", "workbench"), capabilities))
                .contains("does not require a new construction plan",
                        "configuration.prepare-metadata-apply", "including manual edits",
                        "closing the editor does not discard it", "not persisted conversation history",
                        "rules.prepare-apply",
                        "configurationEditor.visible", "reopen the shared editor",
                        "live workspace may still retain unsaved changes",
                        "do not repeat module aliases");
    }

    @Test
    void deduplicatesSurfaceAndCapabilityKnowledge() {
        AiToolDefinition select = new AiToolDefinition(
                "configuration.select-metadata-module", "Select metadata module", Map.of());

        assertThat(AssistantPlatformKnowledge.appendTo(
                "base", Map.of("surface", "metadata-governance"), List.of(select)))
                .containsOnlyOnce("MuYun metadata governance");
    }
}
