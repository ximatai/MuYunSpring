package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.ability.action.ActionMessage;
import net.ximatai.muyun.spring.ability.action.DataChange;
import net.ximatai.muyun.spring.ability.action.MutationContextHolder;

import java.util.function.IntSupplier;

public final class StandardMutationResultSupport {
    private StandardMutationResultSupport() {
    }

    public static void created(ScopedWeb<?> web, String recordId) {
        created(web.webScopeName(), recordId);
    }

    public static void created(ScopedWeb<?> web, String recordId, String recordLabel) {
        StaticCrudActionResultSupport.created(web.webScopeName(), recordId, recordLabel);
    }

    public static void updated(ScopedWeb<?> web, String recordId) {
        updated(web.webScopeName(), recordId);
    }

    public static void updated(ScopedWeb<?> web, String recordId, String recordLabel) {
        StaticCrudActionResultSupport.updated(web.webScopeName(), recordId, recordLabel);
    }

    public static int deleted(ScopedWeb<?> web, String recordId, IntSupplier action) {
        return countMutation(recordId, action, id -> deleted(web.webScopeName(), id));
    }

    public static int deleted(ScopedWeb<?> web, String recordId, String recordLabel, IntSupplier action) {
        return countMutation(recordId, action, id -> StaticCrudActionResultSupport.deleted(web.webScopeName(), id, recordLabel));
    }

    public static int enabled(ScopedWeb<?> web, String recordId, IntSupplier action) {
        return countMutation(recordId, action, id -> enabled(web.webScopeName(), id));
    }

    public static int enabled(ScopedWeb<?> web, String recordId, String recordLabel, IntSupplier action) {
        return countMutation(recordId, action, id -> StaticCrudActionResultSupport.enabled(web.webScopeName(), id, recordLabel));
    }

    public static int disabled(ScopedWeb<?> web, String recordId, IntSupplier action) {
        return countMutation(recordId, action, id -> disabled(web.webScopeName(), id));
    }

    public static int disabled(ScopedWeb<?> web, String recordId, String recordLabel, IntSupplier action) {
        return countMutation(recordId, action, id -> StaticCrudActionResultSupport.disabled(web.webScopeName(), id, recordLabel));
    }

    public static int sorted(ScopedWeb<?> web, IntSupplier action) {
        int count = action.getAsInt();
        if (count > 0) {
            sorted(web.webScopeName());
        }
        return count;
    }

    public static int deleted(String moduleAlias, String recordId, IntSupplier action) {
        return countMutation(recordId, action, id -> deleted(moduleAlias, id));
    }

    public static int enabled(String moduleAlias, String recordId, IntSupplier action) {
        return countMutation(recordId, action, id -> enabled(moduleAlias, id));
    }

    public static int disabled(String moduleAlias, String recordId, IntSupplier action) {
        return countMutation(recordId, action, id -> disabled(moduleAlias, id));
    }

    public static int sorted(String moduleAlias, IntSupplier action) {
        int count = action.getAsInt();
        if (count > 0) {
            sorted(moduleAlias);
        }
        return count;
    }

    public static void created(String moduleAlias, String recordId) {
        StaticCrudActionResultSupport.created(moduleAlias, recordId);
    }

    public static void updated(String moduleAlias, String recordId) {
        StaticCrudActionResultSupport.updated(moduleAlias, recordId);
    }

    public static void deleted(String moduleAlias, String recordId) {
        StaticCrudActionResultSupport.deleted(moduleAlias, recordId);
    }

    public static void enabled(String moduleAlias, String recordId) {
        StaticCrudActionResultSupport.enabled(moduleAlias, recordId);
    }

    public static void disabled(String moduleAlias, String recordId) {
        StaticCrudActionResultSupport.disabled(moduleAlias, recordId);
    }

    public static void sorted(String moduleAlias) {
        StaticCrudActionResultSupport.sorted(moduleAlias);
    }

    /** Reports a child-resource change scoped to its persisted parent without impersonating a root record. */
    public static void resourceCreated(String moduleAlias, String resourceKey, String parentScope, String recordId) {
        reportResource("platform.crud.created", "新增成功",
                DataChange.resourceRecordCreated(moduleAlias, resourceKey, parentScope, recordId));
    }

    public static void resourceUpdated(String moduleAlias, String resourceKey, String parentScope, String recordId) {
        reportResource("platform.crud.updated", "修改成功",
                DataChange.resourceRecordUpdated(moduleAlias, resourceKey, parentScope, recordId));
    }

    public static int resourceDeleted(String moduleAlias, String resourceKey, String parentScope, String recordId,
                                      IntSupplier action) {
        return countMutation(recordId, action, id -> reportResource("platform.crud.deleted", "删除成功",
                DataChange.resourceRecordDeleted(moduleAlias, resourceKey, parentScope, id)));
    }

    private static void reportResource(String code, String text, DataChange change) {
        MutationContextHolder.current().ifPresent(context -> {
            context.message(ActionMessage.success(code, text));
            context.record(change);
        });
    }

    private static int countMutation(String recordId,
                                     IntSupplier action,
                                     java.util.function.Consumer<String> successReporter) {
        int count = action.getAsInt();
        if (count > 0) {
            successReporter.accept(recordId);
        }
        return count;
    }
}
