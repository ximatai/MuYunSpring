package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.List;

/** Producer-owned active configuration text facts; callers cannot manufacture originals. */
public interface ConfiguredTextSourceProvider {
    List<TextReference> currentSources(String moduleAlias);
}
