package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ModuleActionDataAuthResolverTest {
    @Test
    void staticDefaultsFollowServiceAbilities() {
        var modules = mock(PlatformModuleService.class);
        var module = new PlatformModule();
        module.setModuleKind(ModuleKind.STATIC);
        when(modules.select("sales.expense")).thenReturn(module);
        var catalog = mock(StaticAbilityCatalog.class);
        CrudAbility<?> unscoped = mock(CrudAbility.class);
        when(unscoped.getModuleAlias()).thenReturn("sales.expense");
        CrudAbility<?> scoped = mock(CrudAbility.class, withSettings().extraInterfaces(DataScopeAbility.class));
        when(scoped.getModuleAlias()).thenReturn("sales.expense");
        var resolver = new ModuleActionDataAuthResolver(modules, mock(ModuleMetadataRelationService.class),
                mock(MetadataService.class), catalog);
        var action = runtimeAction();
        action.setModuleAlias("sales.expense");
        when(catalog.abilities()).thenReturn(List.of(unscoped));
        assertThat(resolver.resolve(action)).isFalse();
        when(catalog.abilities()).thenReturn(List.of(scoped));
        assertThat(resolver.resolve(action)).isTrue();
        action.setDataAuthOverride(false);
        assertThat(resolver.resolve(action)).isFalse();
    }

    @Test
    void resolvesEntityAliasAndTracksCapabilityChangesWithoutRewritingIntent() {
        var modules = mock(PlatformModuleService.class);
        var relations = mock(ModuleMetadataRelationService.class);
        var metadata = mock(MetadataService.class);
        var module = new PlatformModule();
        module.setModuleKind(ModuleKind.DYNAMIC);
        when(modules.select("sales.expense")).thenReturn(module);
        var relation = new ModuleMetadataRelation();
        relation.setRelationAlias("main");
        relation.setMetadataId("metadata");
        when(relations.list(any(), any(), any())).thenReturn(List.of(relation));
        var entity = new Metadata();
        entity.setAlias("expense");
        entity.setDataScopeEnabled(false);
        when(metadata.select("metadata")).thenReturn(entity);
        var resolver = new ModuleActionDataAuthResolver(modules, relations, metadata, new StaticAbilityCatalog(List.of()));
        var action = runtimeAction();
        action.setModuleAlias("sales.expense");
        action.setEntityAlias("expense");

        assertThat(resolver.resolve(action)).isFalse();
        assertThat(action.getDataAuth()).isTrue();
        entity.setDataScopeEnabled(true);
        assertThat(resolver.resolve(action)).isTrue();
        action.setDataAuthOverride(false);
        assertThat(resolver.resolve(action)).isFalse();
    }

    @Test
    void onlyCapabilityDependentDefaultsAreNormalized() {
        var action = runtimeAction();
        assertThat(ModuleActionDataAuthResolver.resolve(action, false)).isFalse();
        assertThat(ModuleActionDataAuthResolver.resolve(action, true)).isTrue();
        action.setDataAuth(false);
        assertThat(ModuleActionDataAuthResolver.resolve(action, true)).isFalse();
        action.setDataAuthOverride(true);
        assertThat(ModuleActionDataAuthResolver.resolve(action, false)).isTrue();
        action.setDataAuthOverride(null);
        action.setDataAuth(true);
        action.setSystemManaged(false);
        assertThat(ModuleActionDataAuthResolver.resolve(action, false)).isTrue();
        action.setSystemManaged(true);
        action.setSourceType(ModuleActionSourceType.WORKFLOW_DEFINITION);
        assertThat(ModuleActionDataAuthResolver.resolve(action, false)).isTrue();
    }

    private PlatformModuleAction runtimeAction() {
        var action = new PlatformModuleAction();
        action.setSystemManaged(true);
        action.setSourceType(ModuleActionSourceType.WORKFLOW_RUNTIME);
        action.setDataAuth(true);
        return action;
    }
}
