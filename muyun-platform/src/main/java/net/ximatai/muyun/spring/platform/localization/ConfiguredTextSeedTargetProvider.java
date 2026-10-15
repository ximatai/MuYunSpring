package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.List;

/** Producer-owned presets and copies may declare a deployed baseline while retaining their own source identity. */
public interface ConfiguredTextSeedTargetProvider {
    List<Target> derivedTargets(String moduleAlias, List<TextReference> originals, String tenantId);

    record Target(TextReference baseline, TextReference target) { }
}
