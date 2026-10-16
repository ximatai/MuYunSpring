package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityFacet;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityDeclarationPolicy;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityModule;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityOperationContext;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityRegistry;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

class StaticServiceAbilityCompilerTest {
    @Test
    void shouldDeriveReferenceDependenciesFromTheDeclaredModelWithoutAServiceMixin() {
        assertThat(StaticServiceAbilityCompiler.compile(new ReferencingService(), CapabilityModuleRegistry.defaultRegistry()))
                .contains(EntityCapability.REFERENCE_DEPENDENCY)
                .doesNotContain(EntityCapability.REFERENCE, EntityCapability.CACHE, EntityCapability.SOFT_DELETE);
    }

    @Test
    void shouldCompileEnableAbilityThroughTheSameCapabilityModuleFacts() {
        PlatformModuleService service = mock(PlatformModuleService.class);

        assertThat(StaticServiceAbilityCompiler.compile(service, CapabilityModuleRegistry.defaultRegistry()))
                .contains(EntityCapability.ENABLE);
        assertThat(StaticServiceAbilityCompiler.standardActions(service, CapabilityModuleRegistry.defaultRegistry()))
                .contains(PlatformAction.ENABLE, PlatformAction.DISABLE);
    }

    @Test
    void shouldCompileFirstPartyCapabilityThroughItsFacetWithoutChangingTheCentralCompiler() {
        StaticCapabilityRegistry registry = () -> List.of(new ApprovalCapabilityModule());

        assertThat(StaticServiceAbilityCompiler.compile(new ApprovalService(), registry))
                .containsExactly(EntityCapability.APPROVAL);
        assertThat(StaticServiceAbilityCompiler.standardOperations(new ApprovalService(), registry))
                .extracting(PlatformOperationDefinition::action)
                .containsExactly(PlatformAction.EXPORT);
    }

    @Test
    void shouldRejectStaticFacetWhoseServiceDoesNotDeclareItsPrerequisite() {
        StaticCapabilityRegistry registry = () -> List.of(new MissingDependencyCapabilityModule());

        assertThatIllegalStateException().isThrownBy(() ->
                        StaticServiceAbilityCompiler.compile(new ApprovalService(), registry))
                .withMessageContaining("APPROVAL requires CRUD");
    }

    @Test void fieldDeclarationsPublishContentActionsWithoutBusinessMutationOperations() {
        var service = new ContentService();
        var registry = CapabilityModuleRegistry.defaultRegistry();
        assertThat(StaticServiceAbilityCompiler.compile(service, registry)).contains(EntityCapability.BUSINESS_CONTENT);
        assertThat(StaticServiceAbilityCompiler.standardActions(service, registry))
                .contains(PlatformAction.CONTENT_EDIT, PlatformAction.CONTENT_CONFIRM);
        assertThat(StaticServiceAbilityCompiler.standardOperations(service, registry)).extracting(PlatformOperationDefinition::action)
                .doesNotContain(PlatformAction.CONTENT_EDIT, PlatformAction.CONTENT_CONFIRM);
        assertThat(StaticServiceAbilityCompiler.compile(new ReferencingService(), registry)).doesNotContain(EntityCapability.BUSINESS_CONTENT);
    }
    private static final class ContentRecord extends StandardEntity {
        @net.ximatai.muyun.spring.common.content.BusinessContent(key = "content-name")
        @net.ximatai.muyun.database.core.annotation.Column(name = "name", type = net.ximatai.muyun.database.core.builder.ColumnType.TEXT)
        private String name;
    }
    private static final class ContentService extends AbstractAbilityService<ContentRecord> {
        @SuppressWarnings("unchecked") ContentService() { super("test.content", ContentRecord.class, mock(BaseDao.class)); }
    }

    private static final class ApprovalService { }

    private static final class ReferencingRecord extends StandardEntity {
        @ReferenceTo(moduleAlias = "test", entityAlias = "target")
        private String targetId;
    }

    private static final class ReferencingService extends AbstractAbilityService<ReferencingRecord> {
        @SuppressWarnings("unchecked")
        ReferencingService() { super("test.source", ReferencingRecord.class, mock(BaseDao.class)); }
    }

    private static final class ApprovalCapabilityModule implements StaticCapabilityModule {
        @Override public EntityCapability capability() { return EntityCapability.APPROVAL; }
        @Override public StaticCapabilityDeclarationPolicy declarationPolicy() {
            return StaticCapabilityDeclarationPolicy.ANNOTATION_OWNED;
        }
        @Override public Optional<StaticCapabilityFacet> staticFacet() {
            return Optional.of(new StaticCapabilityFacet() {
                @Override public boolean supports(Object service) { return service instanceof ApprovalService; }
                @Override public List<PlatformOperationDefinition> standardOperations(StaticCapabilityOperationContext context) {
                    return List.of(new PlatformOperationDefinition("approval", "export", PlatformAction.EXPORT));
                }
            });
        }
    }

    private static final class MissingDependencyCapabilityModule implements StaticCapabilityModule {
        @Override public EntityCapability capability() { return EntityCapability.APPROVAL; }
        @Override public StaticCapabilityDeclarationPolicy declarationPolicy() {
            return StaticCapabilityDeclarationPolicy.ANNOTATION_OWNED;
        }
        @Override public java.util.Set<EntityCapability> dependencies() { return java.util.Set.of(EntityCapability.CRUD); }
        @Override public Optional<StaticCapabilityFacet> staticFacet() {
            return Optional.of(new StaticCapabilityFacet() {
                @Override public boolean supports(Object service) { return service instanceof ApprovalService; }
            });
        }
    }
}
