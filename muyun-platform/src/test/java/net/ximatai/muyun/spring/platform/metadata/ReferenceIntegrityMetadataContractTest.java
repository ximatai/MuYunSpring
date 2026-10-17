package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceIntegrityMetadataContractTest {
    @Test
    void fieldPropertyDraftMustRetainEnabledRequirementIndependentlyOfDeletionPolicy() {
        MetadataFieldReferenceConfig config = new MetadataFieldReferenceConfig();
        config.setTargetModuleAlias("iam.organization");
        config.setRequireEnabled(true);
        config.setTargetUnavailablePolicy(ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY);
        config.setProjectionMappings("title:organizationTitle");
        config.setCandidateSubtitleProjection("code");

        MetadataFieldReferenceConfig restored = MetadataFieldReferenceConfigDraft.fromConfig(config).toConfig();

        assertThat(restored.getRequireEnabled()).isTrue();
        assertThat(restored.getTargetUnavailablePolicy()).isEqualTo(ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY);
        assertThat(restored.getTargetModuleAlias()).isEqualTo("iam.organization");
        assertThat(restored.getProjectionMappings()).isEqualTo("title:organizationTitle");
        assertThat(restored.getCandidateSubtitleProjection()).isEqualTo("code");
        assertThat(MetadataFieldReferenceConfigDraft.fromConfig(new MetadataFieldReferenceConfig()).requireEnabled()).isFalse();
    }
}
