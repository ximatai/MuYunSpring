package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.code.CodeRule;
import net.ximatai.muyun.spring.platform.code.CodeRuleSegmentService;
import net.ximatai.muyun.spring.platform.code.CodeRuleService;
import net.ximatai.muyun.spring.platform.code.CodeSequencePolicyService;
import net.ximatai.muyun.spring.platform.code.CodeValueMappingService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationReferenceDeletionGuardTest {
    @Test
    void shouldRejectEveryProtectedTargetWithOneStableContract() {
        for (ConfigurationReferenceTarget target : ConfigurationReferenceTarget.values()) {
            ConfigurationReferenceContributor contributor = contributor(target, "dependent", "下游配置", "foreignId", "dependent-1");
            ConfigurationReferenceDeletionGuard guard = new ConfigurationReferenceDeletionGuard(List.of(contributor));

            assertThatThrownBy(() -> guard.assertCanDelete(target, "target-1"))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(error -> assertReferencedError((PlatformException) error, target, "dependent", "dependent-1"));
        }
    }

    @Test
    void shouldUseResourceKeyOrderAndAllowUnreferencedDeletion() {
        ConfigurationReferenceContributor later = contributor(ConfigurationReferenceTarget.METADATA_FIELD,
                "zebra", "后置配置", "fieldId", "zebra-1");
        ConfigurationReferenceContributor first = contributor(ConfigurationReferenceTarget.METADATA_FIELD,
                "alpha", "前置配置", "fieldId", "alpha-1");
        ConfigurationReferenceDeletionGuard guard = new ConfigurationReferenceDeletionGuard(List.of(later, first));

        assertThatThrownBy(() -> guard.assertCanDelete(ConfigurationReferenceTarget.METADATA_FIELD, "field-1"))
                .isInstanceOf(PlatformException.class)
                .satisfies(error -> assertReferencedError((PlatformException) error, ConfigurationReferenceTarget.METADATA_FIELD, "alpha", "alpha-1", "field-1"));
        assertThatCode(() -> guard.assertCanDelete(ConfigurationReferenceTarget.METADATA, "metadata-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectDeletingMetadataFieldReferencedByPersistedCodeRule() {
        TestMemoryDao<CodeRule> ruleDao = new TestMemoryDao<>();
        CodeRuleService codeRuleService = new CodeRuleService(ruleDao,
                new CodeRuleSegmentService(new TestMemoryDao<>()),
                new CodeSequencePolicyService(new TestMemoryDao<>()),
                new CodeValueMappingService(new TestMemoryDao<>()));
        CodeRule codeRule = new CodeRule();
        codeRule.setId("rule-1");
        codeRule.setMetadataFieldId("field-1");
        ruleDao.insert(codeRule);

        ConfigurationReferenceContributorConfiguration configuration =
                new ConfigurationReferenceContributorConfiguration();
        ConfigurationReferenceDeletionGuard guard = new ConfigurationReferenceDeletionGuard(List.of(
                configuration.codeRuleMetadataFieldReference(provider(codeRuleService))));

        assertThatThrownBy(() -> guard.assertCanDelete(ConfigurationReferenceTarget.METADATA_FIELD, "field-1"))
                .isInstanceOf(PlatformException.class)
                .satisfies(error -> assertReferencedError((PlatformException) error,
                        ConfigurationReferenceTarget.METADATA_FIELD, "codeRuleMetadataField", "rule-1", "field-1"));
    }

    @Test
    void shouldRegisterStructuredCodeRuleReferencesInSpringContext() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ConfigurationReferenceContributorConfiguration.class)) {
            assertThat(context.getBeansOfType(ConfigurationReferenceContributor.class).values())
                    .extracting(ConfigurationReferenceContributor::target,
                            contributor -> contributor.reference().resourceKey(),
                            contributor -> contributor.reference().referenceField())
                    .contains(
                            org.assertj.core.groups.Tuple.tuple(ConfigurationReferenceTarget.MODULE_METADATA_FIELD,
                                    "codeRule", "moduleMetadataFieldId"),
                            org.assertj.core.groups.Tuple.tuple(ConfigurationReferenceTarget.METADATA_FIELD,
                                    "codeRuleMetadataField", "metadataFieldId"));
        }
    }

    @Test
    void candidateSubtitleProtectsItsDeclaredFieldPathWithoutBlockingUnrelatedFields() {
        var configs = org.mockito.Mockito.mock(MetadataFieldReferenceConfigService.class);
        var fields = org.mockito.Mockito.mock(MetadataFieldService.class);
        var relations = org.mockito.Mockito.mock(ModuleMetadataRelationService.class);
        var source = new ModuleMetadataRelation(); source.setId("source-relation"); source.setMetadataId("source");
        source.setModuleAlias("test.source"); source.setRelationRole(RelationRole.MAIN);
        var target = new ModuleMetadataRelation(); target.setId("target-relation"); target.setMetadataId("target");
        target.setModuleAlias("test.target"); target.setRelationRole(RelationRole.MAIN);
        var owner = new MetadataField(); owner.setId("owner"); owner.setMetadataId("source"); owner.setFieldName("productId");
        var code = new MetadataField(); code.setId("code"); code.setMetadataId("target"); code.setFieldName("code");
        var config = new MetadataFieldReferenceConfig(); config.setId("binding"); config.setMetadataFieldId("owner");
        config.setRelationId("source-relation"); config.setTargetMetadataId("target"); config.setTargetModuleAlias("test.target");
        config.setCandidateSubtitleProjection("code");
        org.mockito.Mockito.when(configs.list(org.mockito.ArgumentMatchers.any(net.ximatai.muyun.database.core.orm.Criteria.class),
                org.mockito.ArgumentMatchers.any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenReturn(List.of(config));
        org.mockito.Mockito.when(configs.findForRelation("owner", "source-relation")).thenReturn(config);
        org.mockito.Mockito.when(fields.select("owner")).thenReturn(owner);
        org.mockito.Mockito.when(fields.list(org.mockito.ArgumentMatchers.any(net.ximatai.muyun.database.core.orm.Criteria.class),
                org.mockito.ArgumentMatchers.any(net.ximatai.muyun.database.core.orm.PageRequest.class)))
                .thenReturn(List.of(owner), List.of(code), List.of(owner), List.of(code), List.of(owner), List.of(code));
        org.mockito.Mockito.when(relations.list(org.mockito.ArgumentMatchers.any(net.ximatai.muyun.database.core.orm.Criteria.class),
                org.mockito.ArgumentMatchers.any(net.ximatai.muyun.database.core.orm.PageRequest.class)))
                .thenReturn(List.of(source), List.of(target), List.of(source), List.of(target), List.of(source), List.of(target));
        var moduleFields = org.mockito.Mockito.mock(ModuleMetadataFieldService.class);
        var contributor = new ConfigurationReferenceContributorConfiguration().fieldReferenceSubtitleReference(provider(configs),
                provider(org.mockito.Mockito.mock(MetadataService.class)), provider(fields), provider(moduleFields), provider(relations));
        assertThat(contributor.findReferenceId("code")).contains("binding");
        assertThat(contributor.findReferenceId("unrelated-code")).isEmpty();
        var changed = new MetadataField(); changed.setFieldName("renamedCode");
        assertThatThrownBy(() -> new ConfigurationReferenceDeletionGuard(List.of(contributor)).assertCanChangeField(code, changed))
                .isInstanceOf(PlatformException.class);
        // A relation-scoped binding or a module-field override makes this stored default inactive.
        org.mockito.Mockito.when(relations.list(org.mockito.ArgumentMatchers.any(net.ximatai.muyun.database.core.orm.Criteria.class),
                org.mockito.ArgumentMatchers.any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenReturn(List.of(source));
        var scoped = new MetadataFieldReferenceConfig(); scoped.setId("scoped");
        org.mockito.Mockito.when(configs.findForRelation("owner", "source-relation")).thenReturn(scoped);
        assertThat(contributor.findReferenceId("code")).isEmpty();
        org.mockito.Mockito.when(configs.findForRelation("owner", "source-relation")).thenReturn(config);
        var override = new ModuleMetadataField(); override.setMetadataFieldId("owner"); override.setReferenceModuleAlias("test.other");
        org.mockito.Mockito.when(moduleFields.listByRelationId("source-relation")).thenReturn(List.of(override));
        assertThat(contributor.findReferenceId("code")).isEmpty();
    }

    private static void assertReferencedError(PlatformException error, ConfigurationReferenceTarget target,
                                              String resourceKey, String referenceId) {
        assertReferencedError(error, target, resourceKey, referenceId, "target-1");
    }

    private static void assertReferencedError(PlatformException error, ConfigurationReferenceTarget target,
                                              String resourceKey, String referenceId, String targetId) {
        assertThat(error.code()).isEqualTo(PlatformErrorCodes.RESOURCE_IN_USE);
        assertThat(error.httpStatus()).isEqualTo(409);
        assertThat(error.scope().moduleAlias()).isEqualTo(target.moduleAlias());
        assertThat(error.details()).containsEntry(target.detailKey(), targetId)
                .containsEntry("referencedResource", resourceKey)
                .containsEntry("referenceId", referenceId);
    }

    private static ConfigurationReferenceContributor contributor(ConfigurationReferenceTarget target, String key,
                                                                  String name, String field, String referenceId) {
        return new ConfigurationReferenceContributor() {
            @Override public ConfigurationReferenceTarget target() { return target; }
            @Override public ConfigurationReference reference() { return new ConfigurationReference(key, name, field); }
            @Override public Optional<String> findReferenceId(String targetId) { return Optional.of(referenceId); }
        };
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public T getObject() { return value; }
        };
    }
}
