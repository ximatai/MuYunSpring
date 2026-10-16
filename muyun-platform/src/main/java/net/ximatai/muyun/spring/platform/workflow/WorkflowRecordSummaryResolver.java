package net.ximatai.muyun.spring.platform.workflow;

public interface WorkflowRecordSummaryResolver {
    WorkflowRecordSummaryResolver NONE = instance -> null;
    default java.util.Map<String, String> modules() { return java.util.Map.of(); }

    default net.ximatai.muyun.spring.platform.module.ModuleTitleCatalog moduleTitles() {
        var result = new java.util.LinkedHashMap<String, net.ximatai.muyun.spring.platform.module.ModuleTitleView>();
        modules().forEach((alias, title) -> result.put(alias, new net.ximatai.muyun.spring.platform.module.ModuleTitleView(alias, title, null)));
        return new net.ximatai.muyun.spring.platform.module.ModuleTitleCatalog(result);
    }

    WorkflowRecordSummary resolve(WorkflowInstance instance);
}
