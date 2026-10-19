package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Dynamic aggregate adapter; numbering and occupation decisions are source-neutral. */
@Component
public class DynamicCodeCoordinator implements DynamicRecordMutationCoordinator {
    private final CodeRuleService rules;
    private final CodeMutationRuntime mutations;
    private final DynamicRecordService records;
    private final CodeBusinessTimeService time;
    private final CodeLedgerEntryService ledger;
    private final Clock clock;
    private final DynamicCodeContextBuilder contexts = new DynamicCodeContextBuilder();

    public DynamicCodeCoordinator(CodeRuleService rules, CodeGenerateService generate, CodePreviewService preview,
            CodeSequenceStateService sequences, CodeLedgerEntryService ledger, CodeRecycleEntryService recycle,
            @Lazy DynamicRecordService records, CodeBusinessTimeService time, Clock clock) {
        this.rules = Objects.requireNonNull(rules);
        this.ledger = Objects.requireNonNull(ledger);
        this.records = Objects.requireNonNull(records);
        this.clock = Objects.requireNonNull(clock);
        this.time = Objects.requireNonNull(time);
        this.mutations = new CodeMutationRuntime(generate, preview, sequences, ledger, recycle, clock);
    }

    @Override public void beforeCreate(String module, String entity, DynamicRecord incoming) {
        if (incoming == null) return;
        defer(incoming, () -> prepare(module, entity, null, incoming, contexts.recordContext(incoming), Map.of(), incoming.getAuthOrganizationId()));
    }
    @Override public void beforeUpdate(String module, String entity, DynamicRecord before, DynamicRecord incoming) {
        if (before == null || incoming == null) return;
        defer(incoming, () -> {
            Map<String, Object> current = contexts.mergedRecordContext(before, incoming);
            String organization = incoming.isExplicitlySet("authOrganizationId") ? incoming.getAuthOrganizationId() : before.getAuthOrganizationId();
            prepare(module, entity, before, incoming, current, contexts.recordContext(before), organization);
        });
    }
    @Override public void beforeRelationChildCreate(String module, String parentEntity, String relation, String entity,
            DynamicRecord parent, DynamicRecord child) {
        if (child == null) return;
        defer(child, () -> prepare(module, entity, null, child, contexts.childRecordContext(parentEntity, relation, parent, entity, child), Map.of(),
                child.getAuthOrganizationId() != null ? child.getAuthOrganizationId() : parent.getAuthOrganizationId()));
    }
    @Override public void beforeRelationChildUpdate(String module, String parentEntity, String relation, String entity,
            DynamicRecord parentBefore, DynamicRecord parentIncoming, DynamicRecord before, DynamicRecord incoming) {
        if (before == null || incoming == null) return;
        defer(incoming, () -> {
            String organization = before.getAuthOrganizationId();
            if (incoming.isExplicitlySet("authOrganizationId")) organization = incoming.getAuthOrganizationId();
            if (organization == null) organization = parentBefore.getAuthOrganizationId();
            prepare(module, entity, before, incoming,
                    contexts.childMergedRecordContext(parentEntity, relation, parentBefore, parentIncoming, entity, before, incoming),
                    contexts.childRecordContext(parentEntity, relation, parentBefore, entity, before), organization);
        });
    }
    private void defer(DynamicRecord record, Runnable preparation) {
        DynamicMutationContext.current().orElseThrow(() -> new IllegalStateException("Business coding requires a dynamic mutation context"))
                .prepareValuesFor(record, preparation);
    }
    private void prepare(String module, String entity, DynamicRecord before, DynamicRecord incoming,
            Map<String, Object> context, Map<String, Object> previous, String organization) {
        try (var tenant = net.ximatai.muyun.spring.common.tenant.TenantContext.use(
                before == null ? incoming.getTenantId() : before.getTenantId())) {
            prepareInTenant(module, entity, before, incoming, context, previous, organization);
        }
    }
    private void prepareInTenant(String module, String entity, DynamicRecord before, DynamicRecord incoming,
            Map<String, Object> context, Map<String, Object> previous, String organization) {
        if (incoming.getId() == null || incoming.getId().isBlank()) incoming.setId(Ids.newId());
        LocalDateTime at = time.resolveBusinessLocalDateTime(organization,
                DynamicMutationContext.current().map(DynamicMutationContext::startedAt).orElseGet(clock::instant));
        var declarations = incoming.getEntity().businessCodes();
        if (declarations.isEmpty()) return;
        List<ResolvedCodeRule> resolved = rules.resolveRules(new ResolveCodeRuleCommand(module, entity, null, null, organization, at))
                .stream().filter(rule -> declarations.containsKey(rule.rule().getFieldName())).toList();
        for (var declaration : declarations.entrySet()) {
            if (resolved.stream().anyMatch(rule -> declaration.getKey().equals(rule.rule().getFieldName()))) continue;
            Object value = context.get(declaration.getKey());
            var owned = ledger.findActiveByTargetAndSourceRecord(module, entity, declaration.getKey(), incoming.getId());
            boolean retained = owned != null && Objects.equals(owned.getCodeValue(), value);
            boolean emptyExplicit = declaration.getValue().trigger() == net.ximatai.muyun.spring.ability.code.CodeTrigger.EXPLICIT
                    && value == null && previous.get(declaration.getKey()) == null;
            if (!retained && !emptyExplicit) throw new IllegalArgumentException("Code rule missing for declared field: " + declaration.getKey());
        }
        Set<String> changed = new LinkedHashSet<>(context.keySet());
        changed.removeIf(key -> Objects.equals(context.get(key), previous.get(key)));
        mutations.prepare(module, entity, before == null ? null : adapt(module, entity, before), adapt(module, entity, incoming),
                resolved, organization, at, context, previous, changed);
    }
    private CodeMutationRecord adapt(String module, String entity, DynamicRecord record) {
        return new CodeMutationRecord() {
            public String getId() { return record.getId(); }
            public Object getValue(String field) { return record.getValue(field); }
            public boolean isExplicitlySet(String field) { return record.isExplicitlySet(field); }
            public void putGeneratedValue(String field, Object value) { record.putGeneratedValue(field, value); }
            public boolean allowsAutomaticGeneration(String field) {
                return record.getEntity().businessCodes().get(field).trigger() == net.ximatai.muyun.spring.ability.code.CodeTrigger.SAVE;
            }
            public String normalizeCode(String field, String value) {
                var definition = record.getEntity().fields().stream().filter(item -> item.code().equals(field)).findFirst().orElseThrow();
                String normalized = (String) definition.behavior().writeRules().normalize(value);
                int limit = definition.length() != null && definition.length() > 0 ? Math.min(definition.length(),
                        net.ximatai.muyun.spring.ability.code.CodeFieldDefinition.MAX_CODE_LENGTH) : net.ximatai.muyun.spring.ability.code.CodeFieldDefinition.MAX_CODE_LENGTH;
                if (normalized != null && normalized.length() > limit)
                    throw new IllegalArgumentException("Business code exceeds the target field length: " + limit);
                return normalized;
            }
            public boolean codeExists(String field, String value) {
                Criteria criteria = Criteria.of().eq(field, value);
                if (record.getId() != null) criteria.ne("id", record.getId());
                return records.entity(module, entity).list(criteria, new PageRequest(0, 1)).stream()
                        .anyMatch(item -> !Objects.equals(item.getId(), record.getId()) && Objects.equals(item.getValue(field), value));
            }
        };
    }
}
