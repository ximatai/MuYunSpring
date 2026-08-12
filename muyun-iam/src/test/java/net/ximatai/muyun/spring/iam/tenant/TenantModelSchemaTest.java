package net.ximatai.muyun.spring.iam.tenant;

import net.ximatai.muyun.database.core.builder.TableWrapper;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.schema.StaticEntityTableMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TenantModelSchemaTest {
    private final StaticEntityTableMapper mapper = new StaticEntityTableMapper();

    @Test
    void shouldMapTenantAsGlobalIdentityRootModel() {
        TableWrapper table = mapper.toTable(Tenant.class);

        assertThat(table.getName()).isEqualTo("iam_tenant");
        assertThat(columnNames(table))
                .contains("id", "tenant_id", "title", "sort_order", "enabled")
                .contains("light_logo_asset_id", "dark_logo_asset_id")
                .doesNotContain("parent_id");
    }

    @Test
    void shouldExposeTenantApplicationTenantAsSingleCascadeReference() {
        TableWrapper table = mapper.toTable(TenantApplication.class);

        assertThat(table.getColumns()).filteredOn(column -> column.getName().equals("tenant_id"))
                .singleElement();
        assertThat(StaticReferenceResolver.plans(TenantApplication.class))
                .singleElement()
                .satisfies(plan -> {
                    assertThat(plan.sourceField()).isEqualTo("tenantId");
                    assertThat(plan.target()).isEqualTo(ReferenceTarget.of("iam", "tenant"));
                    assertThat(plan.integrity().onTargetUnavailable())
                            .isEqualTo(ReferenceTargetUnavailablePolicy.CASCADE_DELETE);
                });
    }

    private Set<String> columnNames(TableWrapper table) {
        Set<String> names = new LinkedHashSet<>();
        if (table.getPrimaryKey() != null) {
            names.add(table.getPrimaryKey().getName());
        }
        table.getColumns().forEach(column -> names.add(column.getName()));
        return names;
    }
}
