package net.ximatai.muyun.spring.platform.module;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/** Authorized directory snapshot with explicit presentation sources, independent of page structure. */
public record ModuleTitleCatalog(Map<String, ModuleTitleView> modules) {
    public ModuleTitleCatalog { modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules)); }
    public Map<String, String> originalTitles() {
        var titles = new LinkedHashMap<String, String>();
        modules.forEach((alias, module) -> titles.put(alias, module.title()));
        return Collections.unmodifiableMap(titles);
    }
}
