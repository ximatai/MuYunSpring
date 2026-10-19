package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.code.*;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Static services use the same mutation runtime as dynamic metadata, without a UI or DynamicRecord. */
@Component
public final class CodeSaveLifecycleListener implements EntitySaveLifecycleListener {
    private final CodeRuleService rules;
    private final CodeMutationRuntime runtime;
    private final CodeBusinessTimeService time;
    private final Clock clock;
    private final CodeLedgerEntryService ledger;
    private CodeTargetCatalog targets;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCodeTargets(CodeTargetCatalog targets) { this.targets = targets; }
    public CodeSaveLifecycleListener(CodeRuleService rules, CodeGenerateService generate, CodePreviewService preview,
            CodeSequenceStateService sequences, CodeLedgerEntryService ledger, CodeRecycleEntryService recycle,
            CodeBusinessTimeService time, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger);
        this.rules = Objects.requireNonNull(rules); this.time = Objects.requireNonNull(time); this.clock = Objects.requireNonNull(clock);
        this.runtime = new CodeMutationRuntime(generate, preview, sequences, ledger, recycle, clock);
    }
    @Override public <T extends EntityContract> void prepareValues(CrudAbility<T> owner, T existing, T incoming) {
        if (!(owner instanceof CodeAbility<?>)) return;
        try (var tenant = net.ximatai.muyun.spring.common.tenant.TenantContext.use(incoming.getTenantId())) {
            prepareInTenant(owner, existing, incoming);
        }
    }
    private <T extends EntityContract> void prepareInTenant(CrudAbility<T> owner, T existing, T incoming) {
        if (!TransactionScopeSupport.isTransactionActive()) throw new IllegalStateException("Business coding requires a business transaction");
        @SuppressWarnings("unchecked") CodeAbility<T> service = (CodeAbility<T>) owner;
        Map<String, BusinessCode> fields = StaticCodeFields.declarations(owner.modelClass());
        rememberGeneratedDraft(incoming, fields.keySet());
        net.ximatai.muyun.spring.common.model.constraint.StaticFieldWriteRules.normalize(owner.modelClass(), incoming);
        Map<String, Object> context = StaticCodeFields.values(incoming);
        Map<String, Object> previous = StaticCodeFields.values(existing);
        var parent = parentOf(service, incoming);
        if (parent != null) {
            String alias = parent.service() instanceof CodeAbility<?> code ? code.codeEntityAlias()
                    : parent.service().getModuleAlias().substring(parent.service().getModuleAlias().lastIndexOf('.') + 1);
            addQualified(context, service.codeEntityAlias(), StaticCodeFields.values(incoming));
            addQualified(previous, service.codeEntityAlias(), StaticCodeFields.values(existing));
            addQualified(context, alias, StaticCodeFields.values(parent.current()));
            addQualified(previous, alias, StaticCodeFields.values(parent.before()));
        }
        String organization = service.codeOrganizationId(incoming);
        if (organization == null && parent != null) {
            if (parent.service() instanceof CodeAbility<?> parentCode) {
                @SuppressWarnings("unchecked") var coded = (CodeAbility<EntityContract>) parentCode;
                organization = coded.codeOrganizationId(parent.current());
            } else if (parent.current() instanceof net.ximatai.muyun.spring.common.model.capability.DataScopeCapable scoped)
                organization = scoped.getAuthOrganizationId();
        }
        LocalDateTime at = time.resolveBusinessLocalDateTime(organization, clock.instant());
        List<ResolvedCodeRule> resolved = rules.resolveRules(new ResolveCodeRuleCommand(owner.getModuleAlias(), service.codeEntityAlias(), null, null, organization, at));
        for (var declaration : fields.entrySet()) {
            if (resolved.stream().noneMatch(rule -> declaration.getKey().equals(rule.rule().getFieldName()))) {
                Object value = context.get(declaration.getKey());
                var owned = ledger.findActiveByTargetAndSourceRecord(owner.getModuleAlias(), service.codeEntityAlias(), declaration.getKey(), incoming.getId());
                boolean retained = owned != null && Objects.equals(owned.getCodeValue(), value);
                boolean emptyExplicit = declaration.getValue().trigger() == CodeTrigger.EXPLICIT && value == null
                        && previous.get(declaration.getKey()) == null;
                if (!retained && !emptyExplicit)
                    throw new IllegalArgumentException("Code rule missing for declared field: " + declaration.getKey());
            }
        }
        resolved = resolved.stream().filter(rule -> {
            BusinessCode field = fields.get(rule.rule().getFieldName());
            if (field == null) throw new IllegalArgumentException("Code rule targets undeclared static field");
            return field.trigger() == CodeTrigger.SAVE || context.get(rule.rule().getFieldName()) != null
                    || previous.get(rule.rule().getFieldName()) != null;
        }).toList();
        Set<String> changed = new LinkedHashSet<>(context.keySet());
        changed.removeIf(key -> Objects.equals(context.get(key), previous.get(key)));
        runtime.prepare(owner.getModuleAlias(), service.codeEntityAlias(), existing == null ? null : adapt(service, existing, previous),
                adapt(service, incoming, context), resolved, organization, at, context, previous, changed);
    }
    private net.ximatai.muyun.spring.ability.child.AggregateChildMutationContext.Parent parentOf(CodeAbility<?> child, EntityContract record) {
        var active = net.ximatai.muyun.spring.ability.child.AggregateChildMutationContext.parentOf(child);
        if (active.isPresent()) return active.get();
        if (targets == null) return null;
        var target = targets.parentOf(child);
        if (target.isEmpty()) return null;
        Object id = StaticCodeFields.values(record).get(target.get().relation().childForeignKeyField());
        if (id == null) return null;
        var parent = target.get().service().select(String.valueOf(id));
        return parent == null ? null : new net.ximatai.muyun.spring.ability.child.AggregateChildMutationContext.Parent(target.get().service(), parent, parent);
    }
    private void addQualified(Map<String, Object> target, String alias, Map<String, Object> values) {
        values.forEach((name, value) -> target.put(alias + "." + name, value));
    }

    private void rememberGeneratedDraft(EntityContract record, Set<String> fields) {
        var rollback = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(CodeDraftRollback.class::isInstance).map(CodeDraftRollback.class::cast)
                .filter(callback -> callback.owner == this).findFirst().orElseGet(() -> {
                    var callback = new CodeDraftRollback(this);
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(callback);
                    return callback;
                });
        rollback.remember(record, fields);
    }
    private static final class CodeDraftRollback implements org.springframework.transaction.support.TransactionSynchronization {
        private final CodeSaveLifecycleListener owner;
        private final Map<EntityContract, Map<String, Object>> originals = new IdentityHashMap<>();
        CodeDraftRollback(CodeSaveLifecycleListener owner) { this.owner = owner; }
        void remember(EntityContract record, Set<String> fields) {
            Map<String, Object> original = originals.computeIfAbsent(record, ignored -> new LinkedHashMap<>());
            var values = StaticCodeFields.values(record);
            for (String field : fields) if (!original.containsKey(field)) original.put(field, values.get(field));
        }
        @Override public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) originals.forEach((record, values) -> values.forEach((field, value) -> StaticCodeFields.write(record, field, value)));
            originals.clear();
        }
    }

    @Override public <T extends EntityContract> void beforeRestore(CrudAbility<T> owner, T record) {
        try (var tenant = net.ximatai.muyun.spring.common.tenant.TenantContext.use(record.getTenantId())) {
            validateRestoration(owner, record);
        }
    }
    private <T extends EntityContract> void validateRestoration(CrudAbility<T> owner, T record) {
        String module;
        String entity;
        Map<String, Object> values;
        Set<String> fields;
        if (owner instanceof CodeAbility<?> coded) {
            module = owner.getModuleAlias(); entity = coded.codeEntityAlias();
            values = StaticCodeFields.values(record);
            fields = StaticCodeFields.declarations(owner.modelClass()).keySet();
        } else if (record instanceof net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord dynamic
                && owner instanceof net.ximatai.muyun.spring.ability.reference.ReferenceTargetProvider provider) {
            module = provider.referenceTarget().moduleAlias(); entity = provider.referenceTarget().entityAlias();
            values = dynamic.getValues();
            fields = rules.selectRuleTreesByBusinessObject(module, entity).stream().map(CodeRule::getFieldName)
                    .collect(java.util.stream.Collectors.toSet());
        } else return;
        for (String field : fields.stream().sorted().toList()) {
            Object value = values.get(field);
            if (value == null || String.valueOf(value).isBlank()) continue;
            ledger.lockTarget(module, entity, field);
            var entry = ledger.findByTargetAndValue(module, entity, field, String.valueOf(value));
            if (entry == null || entry.getStatus() != CodeLedgerStatus.ACTIVE || !Objects.equals(entry.getSourceRecordId(), record.getId()))
                throw net.ximatai.muyun.spring.common.exception.PlatformErrors.conflict("platform.code.restore-conflict",
                        "Business code occupation conflicts with record restoration", Map.of("field", field));
        }
    }
    private <T extends EntityContract> CodeMutationRecord adapt(CodeAbility<T> service, T record, Map<String, Object> values) {
        return new CodeMutationRecord() {
            public String getId() { return record.getId(); }
            public Object getValue(String field) { return values.get(field); }
            public boolean isExplicitlySet(String field) { return true; }
            public boolean allowsAutomaticGeneration(String field) { return StaticCodeFields.declarations(service.modelClass()).get(field).trigger() == CodeTrigger.SAVE; }
            public String normalizeCode(String field, String value) { return StaticCodeFields.normalizeCode(service.modelClass(), field, value); }
            public void putGeneratedValue(String field, Object value) { StaticCodeFields.write(record, field, value); values.put(field, value); }
            public boolean codeExists(String field, String value) {
                return !service.list(Criteria.of().eq(field, value).ne("id", record.getId()), new PageRequest(0, 1)).isEmpty();
            }
        };
    }
}
