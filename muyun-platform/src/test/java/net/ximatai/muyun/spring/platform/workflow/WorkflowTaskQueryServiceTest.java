package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowTaskQueryServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowTaskDao taskDao = mock(WorkflowTaskDao.class);
    private final WorkflowEventDao eventDao = mock(WorkflowEventDao.class);
    private final WorkflowTaskQueryService service = new WorkflowTaskQueryService(taskDao, eventDao);

    @Test
    void shouldQueryMyTodoTasks() {
        service.myTodo("user-1", PageRequest.of(1, 10));

        verify(taskDao).query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class));
    }

    @Test
    void shouldQueryMyDoneTasks() {
        service.myDone("user-1", PageRequest.of(1, 10));

        verify(taskDao).query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class));
    }

    @Test
    void shouldQueryInstanceHistory() {
        service.instanceTasks("instance-1", PageRequest.of(1, 10));
        service.instanceEvents("instance-1", PageRequest.of(1, 10));

        verify(taskDao).query(any(Criteria.class), any(PageRequest.class), any(Sort.class));
        verify(eventDao).query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class));
    }

    @Test
    void shouldRejectBlankQuerySubject() {
        assertThatThrownBy(() -> service.myTodo(" ", PageRequest.of(1, 10)))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> service.instanceEvents(null, PageRequest.of(1, 10)))
                .isInstanceOf(PlatformException.class);
    }
}
