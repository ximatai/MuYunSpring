package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.ability.action.ActionMessage;
import net.ximatai.muyun.spring.ability.action.DataChange;
import net.ximatai.muyun.spring.ability.action.MutationContextHolder;
import net.ximatai.muyun.spring.ability.action.ActionMessageType;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import java.util.Map;

final class StaticCrudActionResultSupport {
    private StaticCrudActionResultSupport() {
    }

    static void created(String moduleAlias, String recordId) {
        created(moduleAlias, recordId, null);
    }

    static void created(String moduleAlias, String recordId, String recordLabel) {
        report("platform.crud.created", successTemplate(recordLabel, "新增"), DataChange.recordCreated(moduleAlias, recordId), recordLabel);
    }

    static void updated(String moduleAlias, String recordId) {
        updated(moduleAlias, recordId, null);
    }

    static void updated(String moduleAlias, String recordId, String recordLabel) {
        report("platform.crud.updated", successTemplate(recordLabel, "修改"), DataChange.recordUpdated(moduleAlias, recordId), recordLabel);
    }

    static void deleted(String moduleAlias, String recordId) {
        deleted(moduleAlias, recordId, null);
    }

    static void deleted(String moduleAlias, String recordId, String recordLabel) {
        report("platform.crud.deleted", successTemplate(recordLabel, "删除"), DataChange.recordDeleted(moduleAlias, recordId), recordLabel);
    }

    static void enabled(String moduleAlias, String recordId) {
        enabled(moduleAlias, recordId, null);
    }

    static void enabled(String moduleAlias, String recordId, String recordLabel) {
        report("platform.crud.enabled", successTemplate(recordLabel, "启用"), DataChange.recordUpdated(moduleAlias, recordId), recordLabel);
    }

    static void disabled(String moduleAlias, String recordId) {
        disabled(moduleAlias, recordId, null);
    }

    static void disabled(String moduleAlias, String recordId, String recordLabel) {
        report("platform.crud.disabled", successTemplate(recordLabel, "停用"), DataChange.recordUpdated(moduleAlias, recordId), recordLabel);
    }

    static void sorted(String moduleAlias) {
        report("platform.crud.sorted", "排序成功", DataChange.collectionChanged(moduleAlias), null);
    }

    private static void report(String code,
                               String template,
                               DataChange change, String recordLabel) {
        MutationContextHolder.current().ifPresent(context -> {
            boolean named = recordLabel != null && !recordLabel.isBlank();
            Map<String, Object> arguments = named ? Map.of("recordLabel", recordLabel.trim()) : Map.of();
            context.message(new ActionMessage(code, TextResources.format(template, arguments), ActionMessageType.SUCCESS,
                    arguments, TextReference.code(named ? code + ".label" : code, template)));
            context.record(change);
        });
    }

    private static String successTemplate(String recordLabel, String action) {
        return recordLabel == null || recordLabel.isBlank() ? action + "成功" : "「{recordLabel}」" + action + "成功";
    }
}
