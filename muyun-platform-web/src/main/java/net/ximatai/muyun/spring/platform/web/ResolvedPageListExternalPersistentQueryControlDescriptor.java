package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import net.ximatai.muyun.spring.common.util.Preconditions;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;

/** Source-neutral descriptor for one module-owned external criterion control. */
public record ResolvedPageListExternalPersistentQueryControlDescriptor(String id,
                                                                       String title,
                                                                       String externalCriteriaKey,
                                                                       ViewControlType uiType,
                                                                       boolean defaultValue)
        implements ResolvedPageListPersistentQueryControlDescriptor {
    public ResolvedPageListExternalPersistentQueryControlDescriptor {
        id = Preconditions.requireText(id, "persistent query control id");
        title = Preconditions.requireText(title, "persistent query control title");
        externalCriteriaKey = Preconditions.requireText(externalCriteriaKey, "external criteria key");
    }

    @Override
    @JsonProperty("source")
    public PageListPersistentQueryControlDefinition.Source source() {
        return PageListPersistentQueryControlDefinition.Source.EXTERNAL;
    }
}
