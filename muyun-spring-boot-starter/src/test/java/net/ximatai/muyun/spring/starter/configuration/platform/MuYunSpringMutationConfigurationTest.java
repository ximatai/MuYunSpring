package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.deletion.RecordDeletionGuard;
import net.ximatai.muyun.spring.common.model.standard.StandardApprovalEntity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MuYunSpringMutationConfigurationTest {
    @Test void installsAllDeletionGuardsLazilyAndRemovesThemWithTheHost() {
        var first = mock(RecordDeletionGuard.class);
        var second = mock(RecordDeletionGuard.class);
        var runner = new ApplicationContextRunner().withUserConfiguration(MuYunSpringMutationConfiguration.class)
                .withBean("firstGuard", RecordDeletionGuard.class, () -> first)
                .withBean("secondGuard", RecordDeletionGuard.class, () -> second);
        @SuppressWarnings("unchecked") BaseDao<Record, String> dao = mock(BaseDao.class);
        var record = new Record(); record.setId("record"); record.setVersion(1);
        when(dao.query(any(), any())).thenReturn(List.of(record));
        when(dao.deleteByIdAndVersion("record", 1)).thenReturn(1);
        var service = new AbstractAbilityService<>("test.guard_host", Record.class, dao) {};
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            verifyNoInteractions(first, second);
            assertThat(service.delete("record", 1)).isEqualTo(1);
            var ordered = inOrder(first, second);
            ordered.verify(first).validate(service, record);
            ordered.verify(second).validate(service, record);
        });
        assertThat(service.delete("record", 1)).isEqualTo(1);
        verifyNoMoreInteractions(first, second);
    }
    static class Record extends StandardApprovalEntity {}
}
