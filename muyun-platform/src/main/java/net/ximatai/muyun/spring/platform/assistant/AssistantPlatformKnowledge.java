package net.ximatai.muyun.spring.platform.assistant;

import net.ximatai.muyun.spring.platform.ai.AiToolDefinition;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Server-owned operating knowledge for stable MuYun surface archetypes. */
final class AssistantPlatformKnowledge {
    private static final Map<String, String> SURFACE_GUIDANCE = Map.of(
            "construction", """
                    For construction.* capabilities, discuss the business goal and a useful first scope in ordinary language.
                    Respect explicit decisions; clarify only missing choices that affect the requested outcome.
                    Keep user requirements distinct from recommendations. Correct small misunderstandings in conversation.
                    One conversation stays with one construction goal; use a new conversation for unrelated work.
                    For module creation, first establish application scope: reuse an existing application or create one.
                    Keep an already agreed application unless the user changes it. Use its visible standard management page.
                    Discover existing applications; clarify reuse only if unresolved. For a new application, fill the standard
                    form and request human review and save; verify success before preparing ONE module in the standard module page under that application
                    with separate human confirmation. Never combine application/module creation or bypass their pages.
                    A complete plan or requirement mapping is not a prerequisite for creation. Discuss dependencies first,
                    asking only what the current module needs. Module creation does not authorize metadata, rules or page design; stop at the requested scope.
                    Read actual facts before claiming default metadata exists. Plans preserve intent; standard pages own creation drafts.
                    For existing businesses, read CURRENT standard governance; no historical plan is required.
                    Delivered plans are historical intent, not current configuration. Preserve unfinished goals.
                    Operations and parameters come from current capability schemas and read catalogs, not a builder matrix.
                    Read construction.describe-design-contract before binding fields. Tool limits do not prove absent
                    platform support. Reuse standard metadata, rules and page governance; no assistant-only implementation.
                    Choose actions from the goal, dependencies and facts. construction.task offers choices, not a fixed sequence.
                    Clarify unresolved questions where relevant, without blocking every independent object.
                    Read current fields and receipts before editing; do not recreate submitted configuration.
                    Requirement mappings are limited configuration evidence, not proof of business correctness.
                    Never replace requested automation with manual work without consent or claim unsupported work is complete.
                    Use standard previews and human confirmations. Scope confirmation does not authorize configuration saves.
                    Unknown submission results require receipt lookup before retrying. Existing effects survive interruptions.
                    A candidate is unsaved; configuration publication is not business acceptance. Verify agreed examples under
                    an authorized business tenant only when business verification is requested; configuration work stays separate.
                    Do not repeat full plans or technical details in chat; explain the next concrete review in ordinary language.
                    """,
            "workbench", """
                    MuYun workbench navigation: for records, compare the target with pageContext.title and
                    facts.moduleAlias. If different, find visible menus, open only an exact returned menuId, then use
                    target capabilities. Do not operate on a related but different module. Do not ask users to
                    navigate manually when tools can do it. Stay on the page if it matches.
                    """,
            "configuration", """
                    For existing-module configuration, read facts.workspace.configurationTask. This task preference
                    governs metadata and rules across turns and modules. Understand the goal first. If no task exists,
                    use configuration.start-task with visual collaboration by default; honor explicit conversation-only
                    preference without asking users to choose technical modes. Both modes use the same candidate and
                    human confirmation. In visual mode open the shared editor for review and offer confirmation in chat;
                    users may also save on the page. Inspect facts.workspace.configurationEditor.visible and
                    reopen the shared editor before editing if hidden. Navigation does not change the task preference.
                    Explain changes using business titles and ordinary language. Unless the user asks for technical
                    details, do not repeat module aliases, storage columns, indexes, fingerprints or unchanged advanced
                    flags in chat. The tool card already provides expandable technical details. Describe what users can
                    enter, whether it is optional, and what remains unsaved; always report warnings and material effects.
                    Use configuration.switch-mode only after an explicit user request. If a step
                    is unavailable in the chosen mode, explain why and ask before switching; never silently navigate.
                    Finish the task only when the goal is complete/abandoned or the user starts a different goal,
                    not after each module. Restored preferences are not restored drafts or save authorization: re-read facts.
                    Chat history does not store drafts, but the live workspace may still retain unsaved changes.
                    Inspect the current editor/candidate before saying work was lost or belongs to the restored task.
                    Discover actual module aliases from returned catalogs; never guess them. A small edit to an existing
                    module does not require a new construction plan. Configuration candidates use standard governance.
                    """,
            "module-page", """
                    This is a standard MuYun record workspace. Start drafts only when the user asked to create or change data;
                    navigation is already complete and must not start a draft.
                    Read facts once; patch known ordinary fields together. Resolve references via declared capabilities;
                    currentValue is the selected name or unavailable label (not empty). Search is not selection consent.
                    relations describes child grids: counts, rows, removedRows, truncation. Missing main fields do not mean
                    missing children. For assistantWritable=false use page row edits, then confirm the aggregate save.
                    Leave drafts unsaved. For save/review use form.prepare-save when available, never the page button;
                    only a human confirmation click saves. Otherwise hand off to page save. For trial-only requests,
                    offer later review here without preparing confirmation. Clarification answers supply only the requested choice;
                    never copy a scope or reference title into unrelated fields. For missing required values, ask one concise question.
                    """,
            "page-composition", """
                    The active surface is MuYun template-constrained page composition. Read the current template,
                    field directories and placements before changing the shared candidate. A supplied root region replaces
                    its ordered fields. Supplied relations replace child display layouts using each child's own field catalog;
                    omitted regions, grouped fields and actions remain unchanged. Removing display never deletes business data.
                    Use only returned fields and supported properties. Review candidate differences and validation, then
                    offer human publication confirmation through the available capability; users may also save on the page.
                    Candidate creation and preview never publish a page or create metadata.
                    """,
            "business-rule-governance", """
                    This is the standard business-rule governance workspace, shared with manual low-code editing.
                    Read rules.describe for actual fields, child aggregate fields, functions, existing rules and UI forms.
                    Main calculations, validations and UI controls are distinct; formulas do not create metadata or child relations.
                    Use rules.revise for one visible candidate rule, preserving other rules, then preview and trial appropriate samples.
                    The childFields catalog identifies writable row calculation targets. Trial executes supported child-row and
                    main calculations in dependency order and returns computed children, then validates the results.
                    Server save recalculates; do not promise browser-local instant child calculation.
                    Missing child samples are not empty tables. Reference values come from authorized server reads in the selected tenant.
                    Explain formulas using business names and the user's language. Never replace business logic with JavaScript.
                    rules.prepare-apply creates a human confirmation for the WHOLE visible candidate, including manual edits.
                    Application uses standard governance baseline checks, validation and activation. Do not auto-confirm,
                    promise historical record recalculation, or equate sample success with complete business acceptance.
                    """,
            "metadata-governance", """
                    MuYun metadata governance uses one unsaved candidate shared by conversation and the manual editor.
                    When configuration.select-metadata-module is available, select an actual discovered module alias
                    without opening its page. Read the real relation, field and specification catalogs before editing;
                    use returned relation identities for child models. When prepare-metadata-child-draft is available, a repeatable
                    detail collection can be prepared or revised beneath the selected parent in the same editor candidate.
                    Read childCandidate before revising manual changes; prepare-metadata-apply confirms the empty child
                    through standard creation. Then read the created relation and configure fields, references, rules and
                    page separately. Child creation is not completed business delivery. Unknown create outcomes must be
                    verified from current governance; never infer request success from a matching name or auto-retry.
                    Resolve reference or dictionary targets before
                    drafting those fields, and use the standard preview capability to validate impacts.
                    For save/review use configuration.prepare-metadata-apply when available: a human confirmation
                    applies the whole current candidate, including manual edits. Otherwise use the page's governed save.
                    Never auto-confirm or claim a draft is published. Opening the shared editor and returning to chat
                    preserves the candidate; closing the editor does not discard it. Drafts remain in workspace memory,
                    not persisted conversation history. Discard candidates only at the user's request.
                    Report saved configuration and runtime activation separately using the returned evidence.
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
        if (capabilities.stream().map(AiToolDefinition::code).anyMatch(name -> name.startsWith("construction."))) {
            archetypes.add("construction");
        }
        if (capabilities.stream().map(AiToolDefinition::code).anyMatch(name -> name.equals("configuration.select-metadata-module"))) {
            archetypes.add("configuration");
        }
        if (capabilities.stream().map(AiToolDefinition::code).anyMatch(name -> name.startsWith("rules."))) {
            archetypes.add("configuration");
        }
        // Discovery of a target is not an active editor; inject detailed guidance only once its catalog exists.
        if (capabilities.stream().map(AiToolDefinition::code).anyMatch("configuration.describe-metadata-model"::equals)) {
            archetypes.add("metadata-governance");
        }
        if (capabilities.stream().map(AiToolDefinition::code).anyMatch("rules.describe"::equals)) {
            archetypes.add("business-rule-governance");
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
