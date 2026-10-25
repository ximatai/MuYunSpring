package net.ximatai.muyun.spring.platform.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.platform.module.ModuleActionInvoker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModuleActionJobIdentityTest {
    @Test void restorationRejectsPlatformAndForeignTenantAccountsAndReservedMode() {
        var json = new ObjectMapper().findAndRegisterModules();
        var identities = mock(TrustedExecutionIdentityResolver.class); var invoker = mock(ModuleActionInvoker.class);
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("identities", identities); beans.registerSingleton("invoker", invoker);
        var handler = new ModuleActionJob(JobScope.TENANT, beans.getBeanProvider(ModuleActionInvoker.class), mock(ScheduledActionPlanDao.class), mock(net.ximatai.muyun.spring.common.platform.JobSystemActionPolicy.class), beans.getBeanProvider(TrustedExecutionIdentityResolver.class), json);
        var ref = new TrustedExecutionIdentityResolver.Reference("user", "tenant-a", Instant.EPOCH, null);
        var run = new JobRun(); run.setId("run"); run.setTenantId("tenant-a"); run.setIdentityMode("INITIATOR");
        run.setParametersJson(JobService.encodeParameters(Map.of("moduleAlias", "test.target", "actionCode", "execute", "arguments", Map.of(),
                "identity", json.convertValue(ref, Map.class))));
        for (var user : List.of(CurrentUser.systemUser("user", "user"), CurrentUser.tenantUser("user", "user", "tenant-b"),
                CurrentUser.tenantUser("user", "user", "tenant-a", null, true))) {
            when(identities.resolve(ref, "test.target", "execute")).thenReturn(Optional.of(new TrustedExecutionIdentityResolver.Identity(user, null)));
            assertThatThrownBy(() -> handler.execute(new JobContext(null, run))).isInstanceOf(JobFailure.class).hasMessageContaining("IDENTITY_WITHDRAWN");
        }
        run.setTenantId("tenant-b");
        assertThatThrownBy(() -> handler.execute(new JobContext(null, run))).isInstanceOf(JobFailure.class).hasMessageContaining("IDENTITY_SCOPE_MISMATCH");
        assertThat(handler.supportsIdentityMode(JobIdentityMode.SERVICE_ACCOUNT.name())).isFalse();
        verifyNoInteractions(invoker);
    }
}
