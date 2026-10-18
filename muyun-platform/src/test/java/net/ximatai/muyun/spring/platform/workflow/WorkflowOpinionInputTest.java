package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowOpinionInputTest {
    @Test void explicitLanguageAppliesOnlyToHumanOpinionsAndNeverTechnicalMessages() {
        var factory = new WorkflowRuntimeEventFactory(); var instance = new WorkflowInstance();
        assertThat(WorkflowOpinionInput.language()).isNull();
        WorkflowOpinionInput.withLanguage("de", () -> {
            var human = factory.taskCompleted(instance, null, "complete", "de-user", "Versand anhalten.", null);
            assertThat(human.getReasonSourceLanguage()).isEqualTo("de");
            assertThat(human.getReason()).isEqualTo("Versand anhalten.");
            var route = new WorkflowRouteInstance(); route.setSelectedReason("Zusätzliche Prüfung erforderlich.");
            var decision = factory.routeSelected(instance, route, "de-user", null);
            assertThat(decision.getReason()).isEqualTo("Zusätzliche Prüfung erforderlich.");
            assertThat(decision.getReasonSourceLanguage()).isEqualTo("de");
            assertThat(factory.nodeActivated(instance, new WorkflowNodeInstance(), "de-user", null).getReasonSourceLanguage()).isNull();
            WorkflowOpinionInput.withLanguage(null, () -> {
                assertThat(factory.taskCompleted(instance, null, "complete", "de-user", "unknown", null).getReasonSourceLanguage()).isNull();
                return null;
            });
            assertThat(WorkflowOpinionInput.language()).isEqualTo("de");
            return null;
        });
        assertThat(WorkflowOpinionInput.language()).isNull();
    }
    @Test void aFailedOrInvalidOperationCannotLeakItsLanguageIntoTheNextOperation() {
        assertThatThrownBy(() -> WorkflowOpinionInput.withLanguage("en", () -> { throw new IllegalStateException("action rejected"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(WorkflowOpinionInput.language()).isNull();
        WorkflowOpinionInput.withLanguage("zh-CN", () -> {
            assertThatThrownBy(() -> WorkflowOpinionInput.withLanguage("not a language", () -> null)).isInstanceOf(IllegalArgumentException.class);
            assertThat(WorkflowOpinionInput.language()).isEqualTo("zh-CN");
            return null;
        });
        assertThat(WorkflowOpinionInput.language()).isNull();
    }
    @Test void manualAssemblyCannotSilentlyDisableFactPublication() {
        assertThatThrownBy(() -> new WorkflowEventWriter(mock(WorkflowEventDao.class), null))
                .isInstanceOf(NullPointerException.class);
    }
    @Test void aFactIsPublishedOnlyAfterTheWriteSucceeds() {
        var dao = mock(WorkflowEventDao.class);
        var publisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        var writer = new WorkflowEventWriter(dao, publisher); var event = new WorkflowEvent();
        writer.insert(event);
        var order = inOrder(dao, publisher); order.verify(dao).insert(event);
        order.verify(publisher).publishEvent(new WorkflowEventWriter.Persisted(event));
        clearInvocations(dao, publisher);
        doThrow(new IllegalStateException("storage unavailable")).when(dao).insert(event);
        assertThatThrownBy(() -> writer.insert(event)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(publisher);
    }
}
