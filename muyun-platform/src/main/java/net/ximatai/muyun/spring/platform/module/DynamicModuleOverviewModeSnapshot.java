package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplateCatalog;

import java.util.Set;

/** Read model for the persisted overview mode and its currently derived capability facts. */
public record DynamicModuleOverviewModeSnapshot(
        String moduleAlias,
        Integer moduleVersion,
        DynamicModuleOverviewMode overviewMode,
        String mainMetadataId,
        Integer mainMetadataVersion,
        Set<String> mainCapabilities, List<String> searchableFields, Set<String> publishedRequiredCapabilities) {
    @JsonProperty
    public List<PlatformFieldPolicy> platformFieldPolicies() {
        return PlatformFieldPolicy.all();
    }

    @JsonProperty
    public List<PlatformPresentationTemplateCatalog.ManagementSkeleton> compositionSkeletons() {
        return PlatformPresentationTemplateCatalog.managementSkeletons();
    }

}
