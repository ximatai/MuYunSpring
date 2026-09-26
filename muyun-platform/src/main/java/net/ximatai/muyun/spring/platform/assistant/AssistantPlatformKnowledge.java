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
                    For construction.* capabilities, discuss business goals before field details.
                    The current conversational builder delivers independent registration tables only. Before offering
                    linked customer archives, multi-line details, separate payment ledgers, computed balances or automatic
                    state transitions, explain that they are not currently delivered by this builder. Preserve the real
                    need; offer a useful phased alternative and ask consent, or retain it as future scope. Never quietly
                    replace these requirements with unrelated text fields. Do not expand a small first release by default.
                    Preserve the user's application name including trial/test qualifiers; explain any proposed rename.
                    Read persistence and persistenceExplanation as authoritative state: UNSAVED_CANDIDATE is not saved,
                    SAVED_REQUIREMENTS is not a usable app or a saved business record. Do not call draft changes saved.
                    Questions about a pending confirmation require a plain-language explanation, not a new proposal.
                    Explain what the click changes, what remains unfinished, and whether existing content is affected.
                    For argument-free tools pass exactly {}. Do not echo the whole requirements plan in ordinary replies. Read the active
                    requirements plan from facts.workspace.constructionPlan or construction.describe. Ask a few
                    consequential scope questions, suggest a small first release, distinguish USER_REQUIREMENT from
                    RECOMMENDATION, and preserve confirmed decisions. Maintain objects, rules, exclusions, unresolved
                    questions and concrete acceptance examples using construction.propose with the current generation.
                    Present construction.prepare-confirmation for human review. Requirements confirmation is never
                    configuration execution or publication approval. Restore saved plans before resuming; re-read
                    available surface capabilities and never claim configuration was built from a requirements plan.
                    If construction.prepare-initialization is available, one confirmed business object can be previewed
                    as a new module and MAIN entity. New business application/module aliases may be proposed for human
                    review here; they are new identities, never existing-object guesses. Initialization requires its own
                    confirmation. Query construction.initialization-status after submission or lost responses. A receipt
                    proves initialization only: fields, relationships, pages, menus and business acceptance remain separate.
                    Never recreate an object with an existing initialization receipt, even after requirements revisions.
                    After initialization, read construction.describe-fields for actual field specifications and baseline.
                    Every inScope/rules/relationships item must have a human-reviewed requirements mapping:
                    section SCOPE/RULE/RELATION, zero-based index, objectKey, mode, fieldName, explanation.
                    FIELD checks existence only, REQUIRED and UNIQUE check actual constraints; MANUAL explicitly
                    means the user must perform and verify the stated behavior, never an automated guarantee.
                    Fixed option sets, automatic calculations, relations and workflows must be UNSUPPORTED in
                    this builder unless the current capability catalog truly supports their governed configuration.
                    Do not label a finite-choice requirement FIELD just because a text field exists, or quietly
                    label it MANUAL. Discuss the changed promise and revise the source requirement first.
                    Map all clauses, preserve deferred goals in outOfScope, use empty fieldName for MANUAL/UNSUPPORTED.
                    A broad multi-field clause needs multiple bindings and an explicit human verification explanation.
                    After confirming or restoring, read construction.task: it derives the next step from actual
                    receipts, configuration and requirement evidence. Stop at scope decisions and human verification.
                    Never auto-confirm a proposal. Explain technical progress in ordinary business language.
                    construction.prepare-fields previews 1–12 ordinary additions with a separate human confirmation.
                    Propose new field names, but never invent specification aliases. Required/unique/indexed are supported;
                    other business rules and relationships remain unimplemented unless separately verified.
                    Read plan.fieldChanges and actual fields before continuing; never recreate already published fields.
                    Use construction.progress to resume delivered objects. Prepare-page compiles existing fields into
                    list, form/detail and quick search; prepare-entry adds the current system workbench menu after page
                    publication. Each has its own human confirmation. Do not ask users to leave chat to publish.
                    Use returned menuId to open the real business page, verify entry/query/detail against requirements,
                    then prepare-acceptance for explicit human sign-off. Select a permitted business tenant using scope
                    capabilities before record creation; never invent tenant identifiers. Publication is not business acceptance.
                    Resolve discrepancies by revising the requirements or using supported governed configuration edits;
                    never silently drop unsupported rules or claim they are implemented. Existing receipts survive revisions.
                    """,
            "workbench", """
                    MuYun workbench navigation: compare the requested business surface with pageContext.title and
                    facts.moduleAlias. If different, find visible menus, open only an exact returned menuId, then use
                    the target surface capabilities. Do not operate on a related but different module. Do not ask users
                    to navigate manually when workbench capabilities can do it. Stay on the page when it already matches.
                    """,
            "module-page", """
                    This is a standard MuYun record workspace. Start drafts only when the user asked to create or change data;
                    navigation is already complete and must not start a draft.
                    Read facts once; patch known ordinary fields together. Resolve references via declared capabilities;
                    currentValue is the selected name or unavailable label (not empty). Search is not selection consent.
                    relations separately describes child grids: read counts, rows, removedRows and truncation. Missing main fields
                    do not mean missing children. Respect assistantWritable=false: hand row edits to the page, retain the full goal,
                    then confirm the aggregate save. Do not loop over unchanged facts.
                    Leave drafts unsaved for review. Present form.prepare-save when available; only a human click saves.
                    Otherwise hand off to the page save action. Clarification answers supply only the requested choice;
                    never copy a scope or reference title into unrelated fields. For missing required values, ask one concise question.
                    """,
            "page-composition", """
                    The active surface is MuYun template-constrained page composition. Read the current template,
                    field directory and placements before changing a visible candidate. A supplied region replaces
                    its ordered root fields; omitted regions, grouped fields, relations and actions remain unchanged.
                    Use only returned fields and supported presentation properties. Preview the current candidate,
                    summarize differences and validation, then stop. The user saves and activates through the page;
                    candidate creation or preview never publishes a page or creates metadata.
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
        if (capabilities.stream().map(AiToolDefinition::code).anyMatch(name -> name.startsWith("construction."))) {
            archetypes.add("construction");
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
