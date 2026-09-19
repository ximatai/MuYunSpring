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
                    MuYun workbench navigation treats business entries as user-visible menu items. When the goal
                    belongs to another business area, use declared workbench capabilities to find matching visible
                    menus, open only an exact returned menuId, then continue from the newly active surface. Do not ask
                    the user to navigate manually when those capabilities can do it.
                    """,
            "module-page", """
                    The active surface is a standard MuYun record workspace shared by static and dynamic modules.
                    Declared capabilities already reflect the current page mode and permissions. In browse mode,
                    search or inspect records. Start a create/edit draft only when the user asked to create or change
                    data; reaching a requested page or record is already complete and must not start a draft. After
                    entering create/edit mode, describe the form once when field facts are needed, patch known ordinary
                    fields together, and resolve references by business title through the declared reference
                    capabilities. Leave drafts unsaved for user review. Do not repeat descriptive reads whose successful
                    result is already available. If required business values remain missing, ask one concise question;
                    the user need not describe page operations.
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
