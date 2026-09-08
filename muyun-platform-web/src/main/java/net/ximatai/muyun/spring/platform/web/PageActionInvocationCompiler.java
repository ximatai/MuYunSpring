package net.ximatai.muyun.spring.platform.web;

import java.util.Map;
import java.util.LinkedHashMap;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;

/** Shared compilation boundary for static and dynamic page invocation facts. */
final class PageActionInvocationCompiler {
    private PageActionInvocationCompiler() { }

    static Map<PageActionAnchor, PageActionInvocation> dynamic(String moduleAlias, PlatformModuleRuntimeAction action) {
        if (action.bindingPending() || action.category() != EntityActionCategory.CUSTOM
                || action.executorType() != EntityActionExecutorType.SERVICE) return Map.of();
        Map<PageActionAnchor, PageActionInvocation> result = new LinkedHashMap<>();
        String level = action.actionLevel().name();
        if (level.equals("LIST") || level.equals("ANY")) result.put(PageActionAnchor.PAGE,
                new PageActionInvocation("POST", "/" + moduleAlias + "/" + action.actionCode(), PageActionInvocation.Input.NONE));
        if (level.equals("RECORD") || level.equals("ANY")) result.put(PageActionAnchor.DETAIL,
                new PageActionInvocation("POST", "/" + moduleAlias + "/" + action.actionCode() + "/{recordId}", PageActionInvocation.Input.NONE));
        if (action.formSupported()) result.put(PageActionAnchor.FORM,
                new PageActionInvocation("POST", "/" + moduleAlias + "/form-actions/" + action.actionCode(), PageActionInvocation.Input.FORM_RECORD));
        return Map.copyOf(result);
    }

    static ResolvedModuleUiDescriptor bind(ResolvedModuleUiDescriptor descriptor,
            Map<String, Map<PageActionAnchor, PageActionInvocation>> invocations) {
        var page = descriptor.page();
        if (page == null || !page.managedActions()) return descriptor;
        var actions = page.actions().stream().map(action -> {
            if (action.operation() != PageActionOperation.INVOKE) return action;
            var invocation = invocations.getOrDefault(action.actionCode(), Map.of()).get(action.anchor());
            if (invocation == null) throw new IllegalArgumentException("页面动作缺少可执行绑定："
                    + action.actionCode() + " / " + action.anchor());
            boolean valid = switch (action.anchor()) {
                case PAGE -> invocation.input() == PageActionInvocation.Input.NONE && !invocation.path().contains("{recordId}");
                case DETAIL -> invocation.input() == PageActionInvocation.Input.NONE && invocation.path().contains("{recordId}");
                case FORM -> invocation.input() == PageActionInvocation.Input.FORM_RECORD;
            };
            if (!valid) throw new IllegalArgumentException("页面动作绑定与区域不匹配：" + action.actionCode() + " / " + action.anchor());
            return new ResolvedPageActionDescriptor(action.actionCode(), action.anchor(), action.title(),
                    action.operation(), invocation);
        }).toList();
        return descriptor.withPage(new ResolvedModulePageDescriptor(page.template(), page.explorer(), page.navigator(),
                page.list(), page.treeResource(), page.detail(), page.traits(), page.quickSearchFields(), actions, page.managedActions()));
    }
}
