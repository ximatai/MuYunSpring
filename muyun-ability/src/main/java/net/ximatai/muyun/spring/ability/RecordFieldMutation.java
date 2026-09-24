package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.child.Children;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Exact service/record binding for a domain command's declared field writes. */
final class RecordFieldMutation {
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private RecordFieldMutation() {}

    static <T extends EntityContract> int update(CrudAbility<T> service, ActionExecutionPolicy policy,
                                                String id, Consumer<T> mutation, Set<String> fields) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(mutation, "mutation");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("record id is required");
        if (fields.isEmpty()) throw new IllegalArgumentException("field mutation requires fields");
        return PlatformAbilityDispatcher.inMutationTransaction(() -> {
            try (var ignored = ActionExecutionContextHolder.use(ActionExecutionContext.ofPolicy(
                    service.getModuleAlias(), policy, Set.of(id), CurrentUserContext.currentUser()))) {
                if (service instanceof DataScopeAbility<?> scoped && policy.requiresDataScope()) {
                    var proof = scoped.verifyMutationScope(service.getModuleAlias(), policy,
                            PlatformAction.UPDATE, Set.of(id));
                    return VerifiedMutationScopeExecutor.execute(service,
                            PlatformAction.UPDATE, Set.of(id), proof,
                            () -> updateRecord(service, id, mutation, fields));
                }
                return updateRecord(service, id, mutation, fields);
            }
        });
    }

    private static <T extends EntityContract> int updateRecord(CrudAbility<T> service, String id,
                                                              Consumer<T> mutation,
                                                              Set<String> fields) {
        T stored = service.selectActiveRaw(id);
        if (stored == null) return 0;
        PlatformAbilityDispatcher.requireMutationContext(service, stored);
        // Resolve and validate the server declaration before copying values or executing domain code.
        var writableFields = fields.stream().map(name -> field(stored.getClass(), name, true)).toList();
        T draft = EntityRecordCopies.forFieldMutation(stored);
        if (service instanceof FieldProtectionAbility<?> protection) {
            @SuppressWarnings("unchecked")
            var typed = (FieldProtectionAbility<T>) protection;
            typed.restoreProtectedFieldsFromStorage(draft);
        }
        T changes = EntityRecordCopies.forFieldMutation(draft);
        mutation.accept(changes);
        writableFields.forEach(field -> copy(field, changes, draft, EntityRecordCopies::forFieldMutation));
        Binding previous = CURRENT.get();
        CURRENT.set(new Binding(service, draft, Set.copyOf(fields)));
        try {
            return service.update(draft);
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    static void retain(CrudAbility<?> service, EntityContract draft, EntityContract existing, String... fields) {
        if (existing == null) return;
        Binding binding = CURRENT.get();
        for (String name : fields) {
            if (binding != null && binding.service == service && binding.record == draft
                    && binding.fields.contains(name)) continue;
            copy(field(draft.getClass(), name, false), existing, draft, UnaryOperator.identity());
        }
    }

    private static Field field(Class<?> model, String name, boolean writable) {
        Class<?> owner = model;
        while (owner != null && owner != Object.class) {
            try {
                Field field = owner.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                        || Modifier.isTransient(field.getModifiers()) || field.isAnnotationPresent(Children.class)
                        || (writable && StandardEntitySchema.fieldNames().contains(name))) {
                    throw new IllegalArgumentException("field is not a business mutation field: " + name);
                }
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                owner = owner.getSuperclass();
            }
        }
        throw new IllegalArgumentException("unknown mutation field: " + name);
    }

    private static void copy(Field field, Object source, Object target, UnaryOperator<Object> copyValue) {
        try {
            field.set(target, copyValue.apply(field.get(source)));
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("cannot copy mutation field: " + field.getName(), failure);
        }
    }

    private record Binding(CrudAbility<?> service, EntityContract record, Set<String> fields) {}
}
