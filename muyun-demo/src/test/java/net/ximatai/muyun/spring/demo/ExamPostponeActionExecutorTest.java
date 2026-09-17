package net.ximatai.muyun.spring.demo;

import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionResultBody;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.platform.module.PlatformDynamicActionContribution;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExamPostponeActionExecutorTest {
    @Test
    void declaresAndExecutesTheCodeOwnedExamAction() {
        PlatformDynamicActionContribution declaration = ExamPostponeActionExecutor.class
                .getAnnotation(PlatformDynamicActionContribution.class);
        assertThat(declaration.moduleAlias()).isEqualTo(ExamDemoBootstrapTask.MODULE_ALIAS);
        assertThat(declaration.actionCode()).isEqualTo(ExamPostponeActionExecutor.ACTION_CODE);
        assertThat(declaration.actionLevel()).isEqualTo(EntityActionLevel.RECORD);
        assertThat(declaration.dataAuth()).isFalse();

        DynamicRecord exam = mock(DynamicRecord.class);
        LocalDate date = LocalDate.of(2026, 9, 16);
        when(exam.getValue("examDate")).thenReturn(date);
        when(exam.setValue("examDate", date.plusDays(1))).thenReturn(exam);
        DynamicActionOperations operations = mock(DynamicActionOperations.class);
        when(operations.select("exam-1")).thenReturn(exam);
        when(operations.update(exam)).thenReturn(1);

        DynamicActionResultBody result = (DynamicActionResultBody) new ExamPostponeActionExecutor().execute(
                null, DynamicActionExecutionRequest.id("exam-1"), operations);

        verify(exam).setValue("examDate", date.plusDays(1));
        verify(operations).update(exam);
        assertThat(result.message()).isEqualTo("考试日期已顺延至 2026-09-17");
        assertThat(result.refresh()).isTrue();
    }
}
