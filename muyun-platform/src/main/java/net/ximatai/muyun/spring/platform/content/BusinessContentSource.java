package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.common.i18n.TextReference;

/** Tenant, module, entity, row and stable field identity; no title/row index participates. */
record BusinessContentSource(String tenantId, String moduleAlias, String entityKey, String recordId, String fieldKey) {
    String key() {
        StringBuilder tuple = new StringBuilder();
        for (String part : new String[]{tenantId, moduleAlias, entityKey, recordId, fieldKey}) {
            if (part == null) tuple.append("-1:");
            else tuple.append(part.length()).append(':').append(part);
        }
        return TextReference.versionOf(tuple.toString()).substring("sha256-".length());
    }
}
