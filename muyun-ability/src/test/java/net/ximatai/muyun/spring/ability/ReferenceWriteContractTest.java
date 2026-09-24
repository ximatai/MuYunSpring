package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateBinding;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrity;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledEntity;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceWriteContractTest {
    @AfterEach
    void reset() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void declarationAloneMustValidateRawFactsWithoutUsingPickerProjections() {
        TargetService target = target();
        SourceService source = new SourceService();
        Source record = source("tenant-a", "division-a");

        assertThat(source.insert(record)).isNotBlank();
        record.divisionId = "division-b";
        assertThatThrownBy(() -> source.update(record))
                .isInstanceOf(PlatformException.class).hasMessageContaining("所选关联记录与当前填写的关联条件不一致")
                .satisfies(error -> {
                    PlatformException exception = (PlatformException) error;
                    assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(exception.targets()).extracting(targetField -> targetField.fieldName()).containsExactly("targetId");
                    assertThat(exception.details()).containsEntry("referenceReason", "DEPENDENCY_MISMATCH")
                            .containsEntry("referenceTarget", "test.target");
                });
        assertThat(target.projectionReads).isZero();
    }

    @Test
    void unchangedReferenceMustStillRequireEnabledTargetOnUpdateAndRestore() {
        TargetService target = target();
        SourceService source = new SourceService();
        Source record = source("tenant-a", "division-a");
        source.insert(record);
        Source deleted = source("tenant-a", "division-a");
        source.insert(deleted);
        assertThat(source.delete(deleted)).isEqualTo(1);
        target.selectActiveRaw("target").setEnabled(false);

        assertThatThrownBy(() -> source.update(record))
                .isInstanceOf(PlatformException.class).hasMessageContaining("所选关联记录已停用");
        assertThatThrownBy(() -> source.restore(deleted.getId()))
                .isInstanceOf(PlatformException.class).hasMessageContaining("所选关联记录已停用");
    }

    @Test
    void enabledRequirementMustRejectDeletedOrMissingTargetEvenForHistoricalReference() {
        TargetService target = target();
        SourceService source = new SourceService();
        Source record = source("tenant-a", "division-a");
        source.insert(record);
        target.selectActiveRaw("target").setDeleted(true);

        assertThatThrownBy(() -> source.update(record))
                .isInstanceOf(PlatformException.class).hasMessageContaining("所选关联记录不存在或已删除");
        assertThatThrownBy(() -> source.insert(source("tenant-a", "division-a")))
                .isInstanceOf(PlatformException.class).hasMessageContaining("所选关联记录不存在或已删除");
    }

    @Test
    void systemContextMustNotAllowSameTenantReferenceToCrossTenants() {
        target();
        SourceService source = new SourceService();
        try (var ignored = TenantContext.system("reference integrity contract")) {
            assertThatThrownBy(() -> source.insert(source("tenant-b", "division-a")))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("所选关联记录不属于当前记录的租户");
        }
    }

    @Test
    void strictReferenceMustRejectTargetWithoutEnabledStateBeforeReadingFacts() {
        PlainTargetService target = new PlainTargetService();
        PlatformAbilityRuntime.configureReferenceTargetResolver(reference -> Optional.of(target));
        assertThatThrownBy(() -> new SourceService().insert(source("tenant-a", "division-a")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires target ENABLE capability: test.target");
    }

    @Test
    void historicalReferenceMayRemainOnlyWhileItsDependencyFactsAreUnchanged() {
        TargetService target = target();
        var service = new AbstractAbilityService<HistoricalSource>(
                "test.history", HistoricalSource.class, new InMemoryBaseDao<>()) {};
        HistoricalSource original = new HistoricalSource();
        original.setTenantId("tenant-a");
        original.targetId = "target";
        original.divisionId = "division-a";
        service.insert(original);
        target.selectActiveRaw("target").setDeleted(true);

        HistoricalSource unchanged = new HistoricalSource();
        unchanged.setId(original.getId());
        unchanged.setVersion(original.getVersion());
        unchanged.targetId = "target";
        unchanged.divisionId = "division-a";
        assertThat(service.update(unchanged)).isEqualTo(1);

        HistoricalSource changed = new HistoricalSource();
        changed.setId(unchanged.getId());
        changed.setVersion(unchanged.getVersion());
        changed.targetId = "target";
        changed.divisionId = "division-b";
        assertThatThrownBy(() -> service.update(changed))
                .isInstanceOf(PlatformException.class).hasMessageContaining("所选关联记录不存在或已删除");
    }

    private static class HistoricalSource extends StandardEntity {
        @ReferenceTo(moduleAlias = "test", entityAlias = "target",
                candidateBindings = @ReferenceCandidateBinding(sourceField = "divisionId", targetField = "divisionId"))
        private String targetId;
        private String divisionId;
    }

    private TargetService target() {
        TargetService service = new TargetService();
        Target target = new Target();
        target.setId("target");
        target.setTenantId("tenant-a");
        target.setTitle("Target");
        target.setEnabled(true);
        target.divisionId = "division-a";
        service.insert(target);
        PlatformAbilityRuntime.configureReferenceTargetResolver(reference ->
                "test.target".equals(reference.qualifiedName()) ? Optional.of(service) : Optional.empty());
        return service;
    }

    private Source source(String tenantId, String divisionId) {
        Source source = new Source();
        source.setTenantId(tenantId);
        source.targetId = "target";
        source.divisionId = divisionId;
        return source;
    }

    private static class Source extends StandardEntity {
        @ReferenceTo(moduleAlias = "test", entityAlias = "target",
                integrity = @ReferenceIntegrity(requireEnabled = true),
                candidateBindings = @ReferenceCandidateBinding(sourceField = "divisionId", targetField = "divisionId"))
        private String targetId;
        private String divisionId;
    }

    private static class Target extends StandardEnabledEntity {
        private String divisionId;
    }

    private static class PlainTargetService extends AbstractAbilityService<StandardTitledEntity>
            implements ReferenceAbility<StandardTitledEntity> {
        PlainTargetService() { super("test.target", StandardTitledEntity.class, new InMemoryBaseDao<>()); }

        @Override
        public Map<String, Map<String, Object>> referenceFacts(Collection<String> ids, Collection<String> fields) {
            throw new AssertionError("invalid target capability must fail before reading records");
        }
    }

    private static class SourceService extends AbstractAbilityService<Source> implements SoftDeleteAbility<Source> {
        SourceService() {
            super("test.source", Source.class, new InMemoryBaseDao<>());
        }
    }

    private static class TargetService extends AbstractAbilityService<Target>
            implements ReferenceAbility<Target>, SoftDeleteAbility<Target> {
        private int projectionReads;

        TargetService() {
            super("test.target", Target.class, new InMemoryBaseDao<>());
        }

        @Override
        public Map<String, Map<String, Object>> projections(Collection<String> ids, Collection<String> fields) {
            projectionReads++;
            throw new AssertionError("integrity must not read presentation projections");
        }
    }
}
