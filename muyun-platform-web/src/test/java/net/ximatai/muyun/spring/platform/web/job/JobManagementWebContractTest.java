package net.ximatai.muyun.spring.platform.web.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.platform.job.JobRun;
import net.ximatai.muyun.spring.platform.job.JobService;
import net.ximatai.muyun.spring.platform.web.ListDetailCardPageDefinition;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JobManagementWebContractTest {
    @Test
    void managementMenusUseTheExistingOperationsGroupAndPublishRecordActions() throws Exception {
        assertThat(JobPlanWebController.class.getAnnotation(PlatformMenu.class).parent())
                .isEqualTo(PlatformMenuGroups.OPS);
        assertThat(JobRunWebController.class.getAnnotation(PlatformMenu.class).parent())
                .isEqualTo(PlatformMenuGroups.OPS);
        for (String name : new String[] {"runNow", "pause", "resume"}) {
            assertThat(JobPlanWebController.class.getMethod(name, String.class)
                    .getAnnotation(CustomActionEndpoint.class).pageInvocable()).isTrue();
        }
        for (String name : new String[] {"cancel", "retry"}) {
            assertThat(JobRunWebController.class.getMethod(name, String.class)
                    .getAnnotation(CustomActionEndpoint.class).pageInvocable()).isTrue();
        }
    }

    @Test
    void jobManagementListsDeclareReadableWidthsForEnglishHeaders() {
        var page = (ListDetailCardPageDefinition) new JobPlanWebController(mock(JobService.class)).moduleUiDefinition().page();
        assertThat(page.list().list().fields()).allSatisfy(field ->
                assertThat(Integer.parseInt(field.width().replace("px", ""))).isGreaterThanOrEqualTo(120));
        assertThat(page.list().list().fields()).filteredOn(field -> field.fieldRef().fieldName().equals("tenantId"))
                .singleElement().satisfies(field -> assertThat(field.width()).isEqualTo("180px"));
        var runs = (ListDetailCardPageDefinition) new JobRunWebController(mock(JobService.class)).moduleUiDefinition().page();
        assertThat(runs.list().list().fields()).allSatisfy(field ->
                assertThat(Integer.parseInt(field.width().replace("px", ""))).isGreaterThanOrEqualTo(120));
    }

    @Test
    void managedScheduleFieldsAreReadOnlyAndRuntimeInputIsNeverDisplayed() throws Exception {
        var jobs = mock(JobService.class);
        var plans = new JobPlanWebController(jobs).moduleUiDefinition();
        var page = (ListDetailCardPageDefinition) plans.page();
        assertThat(page.detail().editor().fields()).filteredOn(field -> field.fieldRef().fieldName().equals("expression"))
                .singleElement().satisfies(field -> assertThat(field.readOnly().formula().expression()).contains("managedBy"));
        assertThat(page.detail().editor().fields()).filteredOn(field -> field.fieldRef().fieldName().equals("configRevision"))
                .singleElement().satisfies(field -> {
                    assertThat(field.visible().constant()).isTrue();
                    assertThat(field.readOnly().formula().expression()).isEqualTo("PRESENT({id})");
                    assertThat(field.readOnly().constant()).isNotEqualTo(true);
                });
        var runs = new JobRunWebController(jobs).moduleUiDefinition();
        String definition = new ObjectMapper().writeValueAsString(runs);
        assertThat(definition).doesNotContain("parametersJson", "leaseToken");

        JobRun run = new JobRun();
        run.setParametersJson("sensitive input");
        run.setLeaseToken("private lease");
        String payload = new ObjectMapper().writeValueAsString(run);
        assertThat(payload).doesNotContain("sensitive input", "private lease", "parametersJson", "leaseToken");
    }
}
