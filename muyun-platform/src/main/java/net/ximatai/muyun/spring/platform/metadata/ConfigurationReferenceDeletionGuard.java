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
        contributors.get().stream()
                .filter(contributor -> contributor.target() == target)
                .sorted(Comparator.comparing(contributor -> contributor.reference().resourceKey()))
                .map(contributor -> new FoundReference(contributor, contributor.findReferenceId(targetId)))
                .filter(found -> found.referenceId().isPresent())
                .findFirst()
                .ifPresent(found -> reject(target, targetId, found.contributor(), found.referenceId().orElseThrow()));
    }

    private void reject(ConfigurationReferenceTarget target, String targetId,
                        ConfigurationReferenceContributor contributor, String referenceId) {
        ConfigurationReference reference = contributor.reference();
        throw new PlatformException(PlatformErrorCodes.RESOURCE_IN_USE, 409,
                "该" + target.resourceName() + "仍有" + contributor.describeReference(referenceId) + "，不能删除",
                ErrorScope.module(target.moduleAlias()).action("delete"),
                List.of(ErrorTarget.record(targetId).module(target.moduleAlias())),
                Map.of(target.detailKey(), targetId,
                        "referencedResource", reference.resourceKey(),
                        "referenceField", reference.referenceField(),
                        "referenceId", referenceId));
    }

    private record FoundReference(ConfigurationReferenceContributor contributor, java.util.Optional<String> referenceId) { }
}
