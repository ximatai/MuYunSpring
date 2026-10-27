package net.ximatai.muyun.spring.platform.web.job;

import net.ximatai.muyun.spring.platform.job.JobSchedule;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated, stateless schedule syntax assistance; never reads or changes a job plan. */
@RestController
@RequestMapping("/platform.schedule")
public class JobScheduleWebController {
    public record CronRequest(String expression) {}
    public record CronValidation(boolean valid, String reason) {}

    @PostMapping("/validate-cron")
    public CronValidation validateCron(@RequestBody CronRequest request) {
        String expression = request == null ? null : request.expression();
        if (expression == null || expression.isBlank() || expression.length() > 256)
            return new CronValidation(false, "INVALID_EXPRESSION");
        try {
            JobSchedule.validateCronExpression(expression);
            return new CronValidation(true, null);
        } catch (IllegalArgumentException invalid) {
            // Classify a parser rejection for presentation; only the scheduler decides validity.
            String reason = expression.trim().split("\\s+").length == 6 && !expression.trim().startsWith("0 ")
                    ? "MINUTE_PRECISION_REQUIRED" : "INVALID_EXPRESSION";
            return new CronValidation(false, reason);
        }
    }
}
