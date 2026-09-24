package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantLayerAbilityTest {
    @Test
    void globalReadDoesNotGrantSystemPrivilegesAndRestoresContextOnFailure() {
        var service = new Layers(new InMemoryBaseDao<>() {
            @Override public List<DemoPlainRecord> list(Criteria criteria, Sort... sorts) {
                assertThat(TenantContext.isSystem()).isFalse();
                assertThat(TenantContext.currentTenantId()).contains("tenant-a");
                throw new IllegalStateException("storage failed");
            }
        });
        try (var tenant = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> service.listGlobal(Criteria.of())).hasMessage("storage failed");
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
    }

    static class Layers extends AbstractAbilityService<DemoPlainRecord> implements TenantLayerAbility<DemoPlainRecord> {
        Layers(BaseDao<DemoPlainRecord, String> dao) { super("test.layers", DemoPlainRecord.class, dao); }
    }
}
