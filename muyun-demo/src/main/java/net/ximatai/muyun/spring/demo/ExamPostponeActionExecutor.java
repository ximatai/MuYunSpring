package net.ximatai.muyun.spring.demo;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionResultBody;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.platform.module.PlatformDynamicActionContribution;

import java.time.LocalDate;

/**
 * A runnable school-demo example of a code-owned dynamic module action.
 *
 * <p>The annotation supplies the module action declaration and executor binding at startup;
 * no action needs to be manually created or bound from module governance.</p>
 */
@PlatformDynamicActionContribution(
        moduleAlias = ExamDemoBootstrapTask.MODULE_ALIAS,
        entityAlias = "exam",
        actionCode = ExamPostponeActionExecutor.ACTION_CODE,
        title = "考试顺延一天",
        actionLevel = EntityActionLevel.RECORD
)
public class ExamPostponeActionExecutor implements DynamicActionExecutor {
    public static final String ACTION_CODE = "postponeOneDay";
    public static final String EXECUTOR_KEY = "education.exam.postpone-one-day";

    @Override
    public String executorKey() {
        return EXECUTOR_KEY;
    }

    @Override
    public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
        throw new PlatformException("考试顺延动作需要动态记录操作上下文");
    }

    @Override
    public Object execute(DynamicActionExecutionContext context,
                          DynamicActionExecutionRequest request,
                          DynamicActionOperations operations) {
        String recordId = request == null ? null : request.recordId();
        if (recordId == null || recordId.isBlank()) {
            throw new PlatformException("考试顺延动作需要考试记录 ID");
        }
        DynamicRecord exam = operations.select(recordId);
        if (exam == null) {
            throw new PlatformException("考试记录不存在: " + recordId);
        }
        Object value = exam.getValue("examDate");
        if (!(value instanceof LocalDate examDate)) {
            throw new PlatformException("考试日期无效，无法顺延: " + recordId);
        }
        LocalDate postponedDate = examDate.plusDays(1);
        exam.setValue("examDate", postponedDate);
        int changed = operations.update(exam);
        return DynamicActionResultBody.changedCount(changed,
                "考试日期已顺延至 " + postponedDate);
    }
}
