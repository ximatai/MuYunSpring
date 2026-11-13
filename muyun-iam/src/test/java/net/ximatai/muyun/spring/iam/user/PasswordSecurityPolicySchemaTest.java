package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.builder.Index;
import net.ximatai.muyun.spring.common.schema.StaticEntityTableMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the database-level ownership backstop: the ownership scope key participates in a unique
 * index so concurrent creations for the same scope cannot both commit. The platform singleton is
 * additionally pinned by its fixed primary key.
 */
class PasswordSecurityPolicySchemaTest {
    @Test
    void ownershipScopeKeyCarriesAUniqueDatabaseIndex() {
        var table = new StaticEntityTableMapper().toTable(PasswordSecurityPolicy.class);

        assertThat(table.getIndexes())
                .filteredOn(Index::isUnique)
                .anySatisfy(index -> assertThat(index.getColumns()).contains("ownership_scope_key"));
    }
}
