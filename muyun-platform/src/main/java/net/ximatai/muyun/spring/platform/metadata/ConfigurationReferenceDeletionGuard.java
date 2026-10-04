package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.ErrorTarget;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies the uniform deletion contract to references declared by configuration domains. */
@Service
public class ConfigurationReferenceDeletionGuard {
    private final java.util.function.Supplier<List<ConfigurationReferenceContributor>> contributors;

    public ConfigurationReferenceDeletionGuard(List<ConfigurationReferenceContributor> contributors) {
        List<ConfigurationReferenceContributor> stableContributors = contributors == null ? List.of() : List.copyOf(contributors);
        this.contributors = () -> stableContributors;
    }

    @Autowired
    public ConfigurationReferenceDeletionGuard(ObjectProvider<ConfigurationReferenceContributor> contributors) {
        this.contributors = () -> contributors.orderedStream().toList();
    }

    public void assertCanDelete(ConfigurationReferenceTarget target, String targetId) {
        assertUnreferenced(target, targetId, false, "不能删除", "delete");
    }

    /** Conservative field evolution gate for domains that declare semantic dependencies. */
    public void assertCanChangeField(MetadataField existing, MetadataField proposed) {
        if (existing == null || proposed == null) return;
        if (Objects.equals(existing.getFieldName(), proposed.getFieldName())
                && Objects.equals(existing.getFieldSpecAlias(), proposed.getFieldSpecAlias())
                && !(Boolean.FALSE.equals(proposed.getEnabled()) && !Boolean.FALSE.equals(existing.getEnabled()))) return;
        assertUnreferenced(ConfigurationReferenceTarget.METADATA_FIELD, existing.getId(), true,
                "不能修改字段名称、规格或停用字段", "update");
    }

    private void assertUnreferenced(ConfigurationReferenceTarget target, String targetId, boolean fieldEvolution,
                                   String reason, String action) {
        for (var contributor : contributors.get().stream()
                .filter(candidate -> candidate.target() == target
                        && (!fieldEvolution || candidate.protectsFieldEvolution()))
                .sorted(Comparator.comparing(candidate -> candidate.reference().resourceKey())).toList()) {
            var referenceId = contributor.findReferenceId(targetId);
            if (referenceId.isPresent()) reject(target, targetId, contributor, referenceId.orElseThrow(), reason, action);
        }
    }

    private void reject(ConfigurationReferenceTarget target, String targetId,
                        ConfigurationReferenceContributor contributor, String referenceId, String reason, String action) {
        ConfigurationReference reference = contributor.reference();
        throw new PlatformException(PlatformErrorCodes.RESOURCE_IN_USE, 409,
                "该" + target.resourceName() + "仍有" + contributor.describeReference(referenceId) + "，" + reason,
                ErrorScope.module(target.moduleAlias()).action(action),
                List.of(ErrorTarget.record(targetId).module(target.moduleAlias())),
                Map.of(target.detailKey(), targetId,
                        "referencedResource", reference.resourceKey(),
                        "referenceField", reference.referenceField(),
                        "referenceId", referenceId));
    }
}
