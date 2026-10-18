package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.support.TestBeanProviders;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessContentReadPolicyTest {
    private static final String MODULE = "test.content";
    private final PlatformModuleActionService actions = mock(PlatformModuleActionService.class);
    private final Reader reader = new Reader();
    private final ContentFixture record = new ContentFixture();
    private BusinessContentService content;

    @BeforeEach @SuppressWarnings("unchecked") void install() {
        ObjectProvider<PlatformModuleActionService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(actions);
        content = new BusinessContentService(mock(BusinessContentStore.class), mock(ActionExecutionPolicyService.class),
                mock(DataScopeCriteriaService.class), TestBeanProviders.empty(DynamicRecordService.class), provider);
        record.setId("text-record"); record.setTitle("private text");
        CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "tenant-a"));
    }
    @AfterEach void reset() { CurrentUserContext.clear(); ActionExecutionContextHolder.clear(); }

    @Test void producerViewKeepsItsResolvedPolicyButNarrowsBatchIdsToTheActualTextRecord() {
        var policy = classifiedView();
        var batch = ActionExecutionContext.ofPolicy(MODULE, policy, Set.of("parent", record.getId()), CurrentUserContext.currentUser());
        try (var ignored = ActionExecutionContextHolder.use(batch)) {
            assertThat(content.readableFields(reader, record)).isEmpty();
            assertProducerIdentity(policy);
            assertThat(ActionExecutionContextHolder.current()).contains(batch);
            verifyNoInteractions(actions);
        }
    }

    @Test void maintenanceAndForeignViewUseTheCurrentProducerViewGovernance() {
        when(actions.findDeclaredExecutionPolicy(MODULE, "view")).thenReturn(Optional.of(classifiedView()));
        for (var entry : List.of(ActionExecutionContext.ofPlatformAction(MODULE, PlatformAction.CONTENT_EDIT,
                        Set.of(record.getId()), CurrentUserContext.currentUser()),
                ActionExecutionContext.ofPlatformAction("other.module", PlatformAction.VIEW,
                        Set.of("readable-parent"), CurrentUserContext.currentUser()))) {
            try (var ignored = ActionExecutionContextHolder.use(entry)) {
                assertThat(content.readableFields(reader, record)).isEmpty();
                assertProducerIdentity(classifiedView());
                assertThat(ActionExecutionContextHolder.current()).contains(entry);
            }
        }
        verify(actions, times(2)).findDeclaredExecutionPolicy(MODULE, "view");
    }

    @Test void anEmbeddedProducerWithoutAStandaloneViewDeclarationUsesTheStandardViewFieldContext() {
        when(actions.findDeclaredExecutionPolicy(MODULE, "view")).thenReturn(Optional.empty());
        assertThat(content.readableFields(reader, record)).containsKey("title");
        assertProducerIdentity(PlatformAction.VIEW.executionPolicy());
    }

    @Test void missingPolicyCatalogueFailsClosedInsteadOfRestoringStandardFieldVisibility() {
        var incomplete = new BusinessContentService(mock(BusinessContentStore.class), mock(ActionExecutionPolicyService.class),
                mock(DataScopeCriteriaService.class), TestBeanProviders.empty(DynamicRecordService.class),
                TestBeanProviders.empty(PlatformModuleActionService.class));
        assertThatThrownBy(() -> incomplete.readableFields(reader, record))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
        assertThat(reader.seen).isNull();
    }

    private void assertProducerIdentity(ActionExecutionPolicy expected) {
        assertThat(reader.seen.moduleAlias()).isEqualTo(MODULE);
        assertThat(reader.seen.recordIds()).containsExactly(record.getId());
        assertThat(reader.seen.actionPolicy()).isEqualTo(expected);
        assertThat(reader.seen.permissionCode()).isEqualTo(MODULE + ":" + expected.permissionActionCode());
        assertThat(reader.seen.currentUser()).isEqualTo(CurrentUserContext.currentUser());
        assertThat(reader.seen.authorizationResult()).isNull();
    }
    private static ActionExecutionPolicy classifiedView() {
        var view = PlatformAction.VIEW.executionPolicy();
        return new ActionExecutionPolicy(view.actionCode(), view.level(), view.accessMode(), true, true,
                view.defaultGrantPolicy(), "classifiedView");
    }
    static class Reader extends AbstractAbilityService<ContentFixture> implements FieldReadAbility {
        ActionExecutionContext seen;
        @SuppressWarnings("unchecked") Reader() { super(MODULE, ContentFixture.class, mock(ContentFixtureDao.class)); }
        @Override public FieldReadPolicy fieldReadPolicy(ActionExecutionContext action) {
            seen = action;
            return "classifiedView".equals(action.actionPolicy().permissionActionCode())
                    ? FieldReadPolicy.readableFields(Set.of("id")) : FieldReadPolicy.allReadable();
        }
    }
}
