package net.ximatai.muyun.spring.platform.web.job;

import net.ximatai.muyun.spring.platform.job.JobSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JobScheduleWebControllerTest {
    private final JobScheduleWebController controller = new JobScheduleWebController();
    @ParameterizedTest
    @ValueSource(strings = {"0 0 0 * * *", "0 */5 * * * *", "0 30 9 * * MON,WED", "0 0 9 LW * *", "0 0 9 ? * MON#2"})
    void acceptsTheActualSchedulersAdvancedGrammar(String expression) {
        assertThat(controller.validateCron(new JobScheduleWebController.CronRequest(expression)).valid()).isTrue();
        assertThatCode(() -> JobSchedule.cron(expression, "Asia/Shanghai")).doesNotThrowAnyException();
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "0 0 * * *", "0 0 25 * * *", "0 0 9 * WRONG *", "garbage"})
    void rejectsMalformedExpressionsWithTheSameParser(String expression) {
        assertThat(controller.validateCron(new JobScheduleWebController.CronRequest(expression)).valid()).isFalse();
        assertThatThrownBy(() -> JobSchedule.cron(expression, "Asia/Shanghai")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void preservesTheMinutePrecisionBoundary() {
        var result = controller.validateCron(new JobScheduleWebController.CronRequest("*/5 * * * * *"));
        assertThat(result.reason()).isEqualTo("MINUTE_PRECISION_REQUIRED");
        assertThat(result.valid()).isFalse();
        assertThat(controller.validateCron(new JobScheduleWebController.CronRequest("0 "+"* ".repeat(200))).valid()).isFalse();
    }
    @Test void deliversReadOnlySyntaxValidationThroughHttp() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.schedule/validate-cron").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expression\":\"0 0 9 ? * MON#2\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/platform.schedule/validate-cron").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expression\":\"0 0 99 * * *\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));
    }
}
