package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.Preconditions;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;

/** A persistent control whose value is resolved by a module-owned external criterion. */
public record PageListExternalPersistentQueryControlDefinition(String externalCriteriaKey,
                                                               String title,
                                                               ViewControlType uiType,
                                                               Object defaultValue)
        implements PageListPersistentQueryControlDefinition {
    public PageListExternalPersistentQueryControlDefinition {
        externalCriteriaKey = Preconditions.requireText(externalCriteriaKey, "external criteria key");
        title = Preconditions.requireText(title, "persistent query control title");
        if (uiType != ViewControlType.SWITCH) {
            throw new IllegalArgumentException("unsupported persistent query control type: " + uiType);
        }
        if (!(defaultValue instanceof Boolean)) {
            throw new IllegalArgumentException("SWITCH persistent query control requires a boolean default value");
        }
    }

    @Override
    public String id() {
        return externalCriteriaKey;
    }

    @Override
    public Source source() {
        return Source.EXTERNAL;
    }
}
