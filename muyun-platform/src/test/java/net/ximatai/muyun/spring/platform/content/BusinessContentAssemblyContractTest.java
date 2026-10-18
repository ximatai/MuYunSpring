package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.content.BusinessContent;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.support.TestBeanProviders;
import net.ximatai.muyun.database.core.annotation.Table;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessContentAssemblyContractTest {
    @Test void requiredProvidersAndEventPublisherCannotBeOmittedInManualAssembly() {
        var store = mock(BusinessContentStore.class);
        var policies = mock(ActionExecutionPolicyService.class);
        var scopes = mock(DataScopeCriteriaService.class);
        var records = TestBeanProviders.empty(DynamicRecordService.class);
        var actions = TestBeanProviders.empty(PlatformModuleActionService.class);
        assertThatNullPointerException().isThrownBy(() -> new BusinessContentService(store, policies, scopes, null, actions));
        assertThatNullPointerException().isThrownBy(() -> new BusinessContentService(store, policies, scopes, records, null));
        assertThatNullPointerException().isThrownBy(() -> new BusinessContentSaveLifecycleListener(store, null));
        assertThatNullPointerException().isThrownBy(() -> new BusinessContentSaveLifecycleListener(null, event -> {}));
    }

    @Test void manuallyAssembledListenerPublishesThePersistedOriginalForAutomaticTranslation() {
        var store = mock(BusinessContentStore.class);
        var events = new ArrayList<Object>();
        var listener = new BusinessContentSaveLifecycleListener(store, events::add);
        var owner = mock(CrudAbility.class);
        var incoming = new AutomaticText(); incoming.setId("record");
        var persisted = new AutomaticText(); persisted.setId("record"); persisted.description = "保存后的原文";
        when(owner.getModuleAlias()).thenReturn("test.automatic");
        when(owner.modelClass()).thenReturn(AutomaticText.class);
        when(owner.selectActiveRaw("record")).thenReturn(persisted);
        var revision = new BusinessContentRevision(); revision.setId("revision");
        revision.setOriginalText(persisted.description); revision.setSourceLanguage("zh-CN");
        when(store.capture(any(), eq(persisted.description), isNull(), isNull())).thenReturn(revision);
        listener.persisted(owner, incoming);
        assertThat(events).containsExactly(new BusinessContentOriginalChanged(owner, persisted, "description", "revision"));
        verify(store).capture(BusinessContentSaveLifecycleListener.source(owner, persisted, "description"),
                persisted.description, null, null);
    }

    @Table(name = "automatic_content")
    static class AutomaticText extends StandardEntity {
        @BusinessContent
        @net.ximatai.muyun.database.core.annotation.Column(name = "description", type = net.ximatai.muyun.database.core.builder.ColumnType.TEXT)
        String description;
    }
}
