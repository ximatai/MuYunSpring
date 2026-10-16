package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowSelectionSettingsTest {
    @BeforeEach void host() { PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
        public <T> T execute(java.util.function.Supplier<T> work) { return work.get(); }
        public void lock(String scope, String key) {}
    }); }
    @AfterEach void reset() { PlatformAbilityRuntime.resetMutationTransactionOperator(); }
    @Test void futureSelectionSettingsPreservePublishedExecutionIdentityAndRequireVersion() {
        var definitions = mock(WorkflowDefinitionService.class);
        var definition = new WorkflowDefinition(); definition.setId("definition"); definition.setVersion(4);
        definition.setAlias("purchase"); definition.setModuleAlias("demo.purchase"); definition.setApprovalEnabled(true);
        definition.setCurrentVersionNo(2); definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED);
        when(definitions.select("definition")).thenReturn(definition);
        var service = service(definitions);
        assertThatThrownBy(() -> service.configureSelection("definition", settings(3, "{amount} > 100")))
                .isInstanceOf(OptimisticLockException.class);
        verify(definitions, never()).update(any());
        service.configureSelection("definition", settings(4, "{amount} > 100"));
        verify(definitions).update(definition);
        assertThat(definition.getAlias()).isEqualTo("purchase");
        assertThat(definition.getApprovalEnabled()).isTrue();
        assertThat(definition.getCurrentVersionNo()).isEqualTo(2);
        assertThat(definition.getDefinitionStatus()).isEqualTo(WorkflowDefinitionStatus.PUBLISHED);
        assertThat(definition.getOrganizationId()).isEqualTo("organization");
        assertThat(definition.getMatchExpression()).isEqualTo("{amount} > 100");
        assertThat(definition.getMatchPriority()).isEqualTo(10);
    }
    @Test void selectionExpressionsCannotMutateBusinessAndArchivedDefinitionsCannotBeReconfigured() {
        var definitions=mock(WorkflowDefinitionService.class);
        var definition=new WorkflowDefinition(); definition.setId("definition"); definition.setVersion(1);
        definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED);
        when(definitions.select("definition")).thenReturn(definition);
        var service=service(definitions);
        assertThatThrownBy(() -> service.configureSelection("definition", settings(1,"{amount} = 100")))
                .isInstanceOf(PlatformException.class);
        definition.setDefinitionStatus(WorkflowDefinitionStatus.ARCHIVED);
        assertThatThrownBy(() -> service.configureSelection("definition", settings(1,null)))
                .hasMessageContaining("已归档");
        verify(definitions,never()).update(any());
    }
    @Test void copiedDraftReturnsTheVersionRequiredForItsFirstEditorSave() {
        var definitions = mock(WorkflowDefinitionService.class);
        var definition = new WorkflowDefinition(); definition.setId("definition");
        when(definitions.select("definition")).thenReturn(definition);
        var versions = mock(WorkflowVersionService.class);
        var published = new WorkflowVersion(); published.setId("published"); published.setDefinitionId("definition");
        published.setVersionNo(1); published.setPublishStatus(WorkflowPublishStatus.PUBLISHED);
        when(versions.list(any(), any(net.ximatai.muyun.database.core.orm.PageRequest.class), any(net.ximatai.muyun.database.core.orm.Sort.class))).thenReturn(java.util.List.of(published));
        var stored = new java.util.concurrent.atomic.AtomicReference<WorkflowVersion>();
        when(versions.insert(any())).thenAnswer(call -> {
            WorkflowVersion draft = call.getArgument(0); draft.setId("draft"); draft.setVersion(0);
            stored.set(copy(draft)); return draft.getId();
        });
        when(versions.select("published")).thenReturn(published);
        when(versions.select("draft")).thenAnswer(call -> copy(stored.get()));
        when(versions.update(any())).thenAnswer(call -> {
            WorkflowVersion draft = call.getArgument(0); draft.setVersion(draft.getVersion() + 1);
            stored.set(copy(draft)); return 1;
        });
        var nodes = mock(WorkflowNodeDefinitionDao.class); var links = mock(WorkflowLinkDefinitionDao.class);
        var compiler = mock(WorkflowDesignCompiler.class);
        when(nodes.query(any(), any(), any(net.ximatai.muyun.database.core.orm.Sort.class))).thenReturn(java.util.List.of());
        when(links.query(any(), any(), any(net.ximatai.muyun.database.core.orm.Sort.class))).thenReturn(java.util.List.of());
        var service = new WorkflowDesignService(definitions, versions, nodes, links, compiler, mock(WorkflowBusinessTaskResolver.class));
        var draft = service.upgrade("definition");
        assertThat(draft.getVersion()).isEqualTo(stored.get().getVersion()).isEqualTo(1);
        assertThatCode(() -> service.save("definition", draft.getId(), draft.getVersion(),
                new WorkflowDesignDocument(java.util.List.of(), java.util.List.of(), null))).doesNotThrowAnyException();
    }
    private WorkflowVersion copy(WorkflowVersion source) {
        var copy = new WorkflowVersion(); copy.setId(source.getId()); copy.setVersion(source.getVersion());
        copy.setDefinitionId(source.getDefinitionId()); copy.setVersionNo(source.getVersionNo()); copy.setPublishStatus(source.getPublishStatus());
        return copy;
    }
    private WorkflowDesignService.SelectionSettings settings(int version,String expression) {
        return new WorkflowDesignService.SelectionSettings(version,"Purchase","organization",expression,10,false);
    }
    private WorkflowDesignService service(WorkflowDefinitionService definitions) {
        return new WorkflowDesignService(definitions,mock(WorkflowVersionService.class),mock(WorkflowNodeDefinitionDao.class),
                mock(WorkflowLinkDefinitionDao.class),new WorkflowDesignCompiler(new WorkflowConditionService(mock(ModuleRecordFacts.class))),
                mock(WorkflowBusinessTaskResolver.class));
    }
}
