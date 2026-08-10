package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.database.core.builder.TableWrapper;
import net.ximatai.muyun.spring.common.schema.StaticEntityTableMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleModelSchemaTest {
    private final StaticEntityTableMapper mapper = new StaticEntityTableMapper();

    @Test
    void shouldMapStableRoleDefaults() {
        assertThat(columnDefault(mapper.toTable(Role.class), "assignment_type")).isEqualTo("'employment'");
        assertThat(columnDefault(mapper.toTable(Role.class), "role_kind")).isEqualTo("'standard'");
        assertThat(columnDefault(mapper.toTable(Role.class), "owner_scope_type")).isEqualTo("'tenant'");
        assertThat(columnDefault(mapper.toTable(Role.class), "owner_scope_key")).isEqualTo("'tenant:'");
        assertThat(columnDefault(mapper.toTable(Role.class), "share_policy")).isEqualTo("'private'");
        assertThat(columnDefault(mapper.toTable(Role.class), "built_in")).isEqualTo("FALSE");
        assertThat(columnDefault(mapper.toTable(Role.class), "system_managed")).isEqualTo("FALSE");
        assertThat(columnDefault(mapper.toTable(Role.class), "system_purpose")).isEqualTo("'none'");
        assertThat(columnDefault(mapper.toTable(AccountRoleGrant.class), "enabled")).isEqualTo("TRUE");
        assertThat(columnDefault(mapper.toTable(EmploymentRoleGrant.class), "enabled")).isEqualTo("TRUE");
        assertThat(columnDefault(mapper.toTable(RoleAction.class), "tenant_scope_policy")).isEqualTo("'currentTenant'");
        assertThat(columnDefault(mapper.toTable(RoleAction.class), "enabled")).isEqualTo("TRUE");
    }

    private String columnDefault(TableWrapper table, String columnName) {
        return table.getColumns().stream()
                .filter(column -> columnName.equals(column.getName()))
                .findFirst()
                .orElseThrow()
                .getDefaultValue();
    }
}
