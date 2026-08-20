package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
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

    private static final class ApprovalService { }

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
