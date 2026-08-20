package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityFacet;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityOperationContext;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModule;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StaticServiceAbilityCompilerTest {
    @Test
    void shouldCompileEnableAbilityThroughTheSameCapabilityModuleFacts() {
        PlatformModuleService service = mock(PlatformModuleService.class);

        assertThat(StaticServiceAbilityCompiler.compile(service)).contains(EntityCapability.ENABLE);
        assertThat(StaticServiceAbilityCompiler.standardActions(service))
                .contains(PlatformAction.ENABLE, PlatformAction.DISABLE);
    }

    @Test
    void shouldCompileFirstPartyCapabilityThroughItsFacetWithoutChangingTheCentralCompiler() {
        CapabilityModuleRegistry registry = new CapabilityModuleRegistry(List.of(new ApprovalCapabilityModule()));

        assertThat(StaticServiceAbilityCompiler.compile(new ApprovalService(), registry))
                .containsExactly(EntityCapability.APPROVAL);
        assertThat(StaticServiceAbilityCompiler.standardOperations(new ApprovalService(), registry))
                .extracting(PlatformOperationDefinition::action)
                .containsExactly(PlatformAction.EXPORT);
    }

    private static final class ApprovalService { }

    private static final class ApprovalCapabilityModule implements CapabilityModule {
        @Override public EntityCapability capability() { return EntityCapability.APPROVAL; }
        @Override public CapabilityActionContribution actionContribution() {
            return new CapabilityActionContribution() {
                @Override public EntityCapability capability() { return EntityCapability.APPROVAL; }
                @Override public List<PlatformAction> standardActions() { return List.of(PlatformAction.EXPORT); }
                @Override public Optional<CapabilityEndpointProjection> endpointProjection(PlatformAction action) {
                    return Optional.empty();
                }
            };
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
}
