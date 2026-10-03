package net.ximatai.muyun.spring.platform.assistant;

import net.ximatai.muyun.spring.platform.ai.AiToolDefinition;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Server-owned operating knowledge for stable MuYun surface archetypes. */
final class AssistantPlatformKnowledge {
    private static final Map<String, String> SURFACE_GUIDANCE = Map.of(
            "construction-discovery", """
                    Construction capabilities are available when the user wants to design or build an application.
                    Their availability does not make ordinary queries or navigation construction work.
                    Plans are optional; discover current platform capabilities and use standard governance with
                    human save confirmations. Do not start a construction workflow for unrelated requests.
                    """,
            "configuration-discovery", """
                    When the user requests configuration changes, use configuration.start-task to establish the
                    collaboration preference. A small edit does not require a new construction plan.
                    Discovery alone does not start an editing workflow or authorize a save.
                    """,
            "construction", """
                    Apply this workflow only to requests advancing the current construction goal. Unrelated reads
                    and navigation use ordinary platform capabilities without construction prerequisites.
                    Discuss the business goal and first scope in ordinary language. Respect decisions; distinguish user
                    requirements from recommendations. Clarify only missing choices affecting the outcome.
                    Preserve unfinished construction intent when answering unrelated requests.
                    For module creation, first establish application scope: reuse an existing application or create one.
                    Keep the agreed application; use its visible standard management page.
                    Inspect the requested scope; order changes by actual dependencies. Clarify unresolved choices only.
                    Catalog readiness does not prove goal completion. No mandatory single-module calibration.
                    Discover existing applications; clarify reuse only if unresolved. For a new application, fill the standard
                    form and request human review and save; verify success before preparing a module in the standard module page under that application
                    with separate human confirmation. Never combine application/module creation or bypass their pages.
                    New module aliases must start with selected applicationAlias + '.'; choose technical IDs for users.
                    Plans are optional. For plan progress, associate objects with discovered moduleAlias values through
                    plan review; this creates no configuration or initialization receipt. Respect requested scope.
                    Read actual facts before claiming default metadata exists. Plans preserve intent; standard pages own creation drafts.
                    For existing businesses, read CURRENT standard governance; no historical plan is required.
                    Delivered plans are historical intent, not current configuration. Preserve unfinished goals.
                    Operations and parameters come from current capability schemas and read catalogs, not a builder matrix.
                    For a requirements-only review, propose business scope with requirements=[]; defer technical field
                    bindings and construction.describe-design-contract until needed for implementation.
                    Read construction.describe-design-contract before binding fields. Reuse standard metadata, rules
                    and page governance; no assistant-only implementation. Tool limits do not prove absent platform support.
                    construction.task offers choices, not a fixed sequence. Clarify only relevant unresolved dependencies.
                    Read current fields and receipts before editing; do not recreate submitted configuration.
                    Requirement mappings are limited configuration evidence, not proof of business correctness.
                    Never replace requested automation with manual work without consent or claim unsupported work is complete.
                    Use standard previews and human confirmations. Scope confirmation does not authorize configuration saves.
                    Unknown submission results require receipt lookup before retrying. Existing effects survive interruptions.
                    A candidate is unsaved; configuration publication is not business acceptance. Verify agreed examples under
                    an authorized business tenant only when business verification is requested.
                    Use facts.workspace.constructionPlan for current generation and persistence; construction.describe
                    is for missing design details, not a mandatory step. After proposing the requested review candidate,
                    prepare human confirmation instead of continuing discovery or navigation unless an unresolved
                    dependency changes its scope. Reuse current observations; do not reread facts already supplied.
                    """,
            "workbench", """
                    MuYun workbench navigation: match pageContext.title and facts.moduleAlias; open exact returned menuIds.
                    Current-list absence is not global absence. For missing existing records, find matching visible menus
                    and clarify ambiguous scope before offering creation. Preserve the user's explicit business scope.
                    Never ask users to navigate manually. Configuration governance requires an explicit configuration goal.
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
                    A standard MuYun record workspace. Draft only when the user asked to create or change data;
                    navigation is already complete and must not start a draft.
                    navigator.start-create: navigatorCreationTargets; record.start-create: facts.moduleAlias.
                    editorOwner; creation.ready only controls opening another draft; fill an existing writable form.
                    Reuse facts; patch known ordinary fields together before resolving remaining references.
                    Opening is not filling. Tree children need saved parents; aggregate children save together.
                    References use capabilities; currentValue is a label, not empty. Search is not consent.
                    Use CURRENT edit facts; select writable rows; row tools appear after selection.
                    Review/trial/compare: form.review-draft, no save proposal.
                    Explicit save: form.prepare-save if available (load if indexed);
                    only human confirmation saves. Do not replace an available confirmation with page-save instructions.
                    Explicit discard: form.prepare-discard with human confirmation, local draft only.
                    Without save preparation, hand off to the page save action, never a selection card.
                    Draft-only requests remain unsaved.
                    Missing values: ask one concise question. Never copy scope/reference answers into unrelated fields.
                    Missing fields: say what remains unsaved. Do not offer schema changes.
                    """,
            "page-composition", """
                    The active surface is MuYun template-constrained page composition. Read the current template,
                    field directories and placements before changing the shared candidate. A supplied root region replaces
                    its ordered fields. Supplied relations replace child display layouts using each child's own field catalog;
                    omitted regions, grouped fields and actions remain unchanged. Removing display never deletes business data.
                    Use only returned fields and supported properties. Review candidate differences and validation, then
                    offer human publication confirmation through the available capability; users may also save on the page.
                    Reuse supplied catalogs and the current candidate. Once the user's requested changes are expressible,
                    revise and preview the candidate; do not repeat discovery. Opening the editor does not change the candidate.
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
        Set<String> capabilityCodes = new LinkedHashSet<>();
        capabilities.stream().map(AiToolDefinition::code).forEach(capabilityCodes::add);
        // Schema loading must not remove server-owned operating guidance. The index selects only
        // fixed guidance below; its descriptions are never promoted to instructions or authorization.
        if (context.get("facts") instanceof Map<?, ?> facts && facts.get("capabilityIndex") instanceof List<?> index) {
            index.stream().limit(64).filter(Map.class::isInstance).map(Map.class::cast)
                    .map(item -> item.get("code")).filter(String.class::isInstance).map(String.class::cast)
                    .filter(code -> code.length() <= 128).forEach(capabilityCodes::add);
        }
        Object surface = context.get("surface");
        if (surface instanceof String value && SURFACE_GUIDANCE.containsKey(value)) {
            archetypes.add(value);
        }
        if (capabilityCodes.stream().anyMatch(name -> name.startsWith("workbench."))) {
            archetypes.add("workbench");
        }
        Map<?, ?> workspace = workspace(context);
        if (capabilityCodes.stream().anyMatch(name -> name.startsWith("construction."))
                && !archetypes.contains("construction")) {
            archetypes.add(activeConstruction(workspace) ? "construction" : "construction-discovery");
        }
        if (capabilityCodes.stream().anyMatch(name -> name.startsWith("configuration.") || name.startsWith("rules."))) {
            boolean configurationActive = hasGoal(workspace.get("configurationTask"))
                    || Set.of("configuration", "metadata-governance", "business-rule-governance", "page-composition").contains(surface instanceof String name ? name : "")
                    || capabilityCodes.contains("configuration.describe-metadata-model")
                    || capabilityCodes.contains("rules.describe");
            archetypes.add(configurationActive ? "configuration" : "configuration-discovery");
        }
        // Discovery of a target is not an active editor; inject detailed guidance only once its catalog exists.
        if (capabilityCodes.stream().anyMatch("configuration.describe-metadata-model"::equals)) {
            archetypes.add("metadata-governance");
        }
        if (capabilityCodes.stream().anyMatch("rules.describe"::equals)) {
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
    private static Map<?, ?> workspace(Map<String, Object> context) {
        return context.get("facts") instanceof Map<?, ?> facts && facts.get("workspace") instanceof Map<?, ?> workspace
                ? workspace : Map.of();
    }

    private static boolean hasGoal(Object value) {
        return value instanceof Map<?, ?> task && task.get("goal") instanceof String goal && !goal.isBlank();
    }

    private static boolean activeConstruction(Map<?, ?> workspace) {
        Object value = workspace.get("constructionPlan");
        return hasGoal(value) && value instanceof Map<?, ?> plan
                && !Boolean.TRUE.equals(plan.get("historicalDesign"))
                && !"DELIVERED".equals(plan.get("constructionStatus"));
    }

}
