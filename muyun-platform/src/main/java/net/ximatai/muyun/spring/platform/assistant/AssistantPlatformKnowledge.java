package net.ximatai.muyun.spring.platform.assistant;

import net.ximatai.muyun.spring.platform.ai.AiToolDefinition;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Server-owned operating knowledge for stable MuYun surface archetypes. */
final class AssistantPlatformKnowledge {
    private static final Map<String, String> SURFACE_GUIDANCE = Map.of(
            "workbench", """
                    MuYun workbench navigation: compare the requested business surface with pageContext.title and
                    facts.moduleAlias. If different, find visible menus, open only an exact returned menuId, then use
                    the target surface capabilities. Do not operate on a related but different module. Do not ask users
                    to navigate manually when workbench capabilities can do it. Stay on the page when it already matches.
                    """,
            "module-page", """
                    This is a standard MuYun record workspace for static and dynamic modules. Capabilities reflect
                    mode and permissions. Start drafts only when the user asked to create or change data; navigation
                    is already complete and must not start a draft. Resolve creation prerequisites before creating.
                    Read form facts once when needed, patch known ordinary fields together and resolve references
                    through declared capabilities. Leave drafts unsaved for review. Never ask for save confirmation when no
                    save capability exists; directly hand the draft to the user for the page save action. A clarification answer supplies
                    only the requested choice; never copy a scope or reference title into unrelated fields.
                    For missing required values, ask one concise question based on actual form facts.
                    """,
            "metadata-governance", """
                    The active surface is MuYun metadata governance. Work only on the visible unsaved candidate.
                    Describe the selected model when facts are missing, resolve reference or dictionary targets before
                    drafting those fields, and use the standard preview capability to validate impacts. Never claim a
                    draft is published; the user reviews and applies it through the page's governed save flow.
                    """
    );

    private AssistantPlatformKnowledge() {
    }

    static String appendTo(String basePrompt, Map<String, Object> context, List<AiToolDefinition> capabilities) {
        Set<String> archetypes = new LinkedHashSet<>();
        Object surface = context.get("surface");
        if (surface instanceof String value && SURFACE_GUIDANCE.containsKey(value)) {
            archetypes.add(value);
        }
        if (capabilities.stream().map(AiToolDefinition::code).anyMatch(name -> name.startsWith("workbench."))) {
            archetypes.add("workbench");
        }
        if (archetypes.isEmpty()) return basePrompt;

        String guidance = archetypes.stream()
                .map(SURFACE_GUIDANCE::get)
                .map(String::strip)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow();
        return basePrompt + "\n\nPlatform operating knowledge:\n" + guidance;
    }
}
