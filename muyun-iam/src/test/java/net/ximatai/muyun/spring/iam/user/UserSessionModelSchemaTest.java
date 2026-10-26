package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.builder.TableWrapper;
import net.ximatai.muyun.spring.common.schema.StaticEntityTableMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserSessionModelSchemaTest {
    private final StaticEntityTableMapper mapper = new StaticEntityTableMapper();

    @Test
    void shouldMapUserSessionAsPersistentLoginSession() {
        TableWrapper table = mapper.toTable(UserSession.class);

        assertThat(table.getName()).isEqualTo("iam_user_session");
        assertThat(columnNames(table))
                .contains("id", "tenant_id", "user_id", "username", "organization_id",
                        "token_hash", "issued_at", "expires_at", "max_expires_at",
                        "last_seen_at", "password_change_required", "login_ip", "login_user_agent",
                        "revoked_at", "revoked_reason",
                        "deleted", "version", "created_at", "updated_at");
        for (String time : new String[]{"revoked_at", "expires_at", "max_expires_at"}) {
            assertThat(table.getIndexes()).anySatisfy(index -> {
                assertThat(index.isUnique()).isFalse();
                assertThat(index.getColumns()).containsExactly(time, "id");
            });
        }
        assertThat(table.getIndexes())
                .anySatisfy(index -> {
                    assertThat(index.isUnique()).isTrue();
                    assertThat(index.getColumns()).containsExactly("tenant_id", "token_hash");
                })
                .anySatisfy(index -> {
                    assertThat(index.isUnique()).isFalse();
                    assertThat(index.getColumns()).containsExactly("tenant_id", "user_id", "revoked_at");
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
