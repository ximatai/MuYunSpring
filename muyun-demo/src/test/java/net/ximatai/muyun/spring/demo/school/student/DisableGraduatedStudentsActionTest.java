package net.ximatai.muyun.spring.demo.school.student;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisableGraduatedStudentsActionTest {
    @Test
    void rejectsUnexpectedParameters() {
        var action = new DisableGraduatedStudentsAction(mock(StudentService.class));
        action.validate(Map.of());
        assertThatThrownBy(() -> action.validate(Map.of("tenantId", "another")))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                .hasMessage("停用已毕业学生不接受参数");
    }

    @Test
    void delegatesToDomainServiceUsingUtcDateAndReturnsCount() {
        var students = mock(StudentService.class);
        var today = LocalDate.now(ZoneOffset.UTC);
        when(students.disableGraduated(today)).thenReturn(3);
        var action = new DisableGraduatedStudentsAction(students);
        assertThat(action.moduleAlias()).isEqualTo("education.student");
        assertThat(action.actionCode()).isEqualTo("disableGraduated");
        assertThat(action.execute(Map.of())).isEqualTo(Map.of("disabledStudents", 3));
        verify(students).disableGraduated(today);
    }
}
