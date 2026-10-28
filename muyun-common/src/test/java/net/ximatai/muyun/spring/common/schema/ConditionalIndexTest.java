package net.ximatai.muyun.spring.common.schema;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.*;
import net.ximatai.muyun.spring.common.model.constraint.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.security.EncryptedField;
import org.junit.jupiter.api.Test;

import java.util.List;
import static net.ximatai.muyun.spring.common.model.constraint.IndexCondition.Operator.*;
import static org.assertj.core.api.Assertions.*;

class ConditionalIndexTest {
    @Test void tenantNormalizationPreservesNameDirectionAndDifferentPredicates() {
        var table = new StaticEntityTableMapper().toTable(Plain.class);
        table.getIndexes().add(new Index(List.of(IndexColumn.desc("code")), true, "deleted = false").named("ux_live"));
        table.getIndexes().add(new Index(List.of(IndexColumn.desc("code")), true, "deleted = true").named("ux_retained"));
        PlatformUniqueIndexes.normalizeTenantUniqueIndexes(table);
        PlatformUniqueIndexes.normalizeTenantUniqueIndexes(table);
        assertThat(table.getIndexes()).hasSize(2).allSatisfy(index ->
                assertThat(index.getIndexColumns()).containsExactly(IndexColumn.asc("tenant_id"), IndexColumn.desc("code")));
        assertThat(table.getIndexes()).extracting(Index::getName).containsExactly("ux_live", "ux_retained");
        assertThat(table.getIndexes()).extracting(Index::getPredicate).containsExactly("deleted = false", "deleted = true");
    }

    @Test void conditionalAndUnconditionalUniqueFactsMustCoexist() {
        var constraints = StaticTenantUniqueConstraints.resolve(Unique.class);
        assertThat(constraints).hasSize(3);
        assertThat(constraints.stream().map(TenantUniqueConstraintDefinition::identity).distinct()).hasSize(3);
        assertThat(new StaticEntityTableMapper().toTable(Unique.class).getIndexes()).hasSize(3);
    }

    @Test void rejectsInvalidDeclarationsBeforeDDL() {
        assertThatThrownBy(() -> new IndexCondition("deleted", IN_CODES, List.of("x'); DROP TABLE t; --"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndexCondition("deleted", IS_FALSE, List.of("x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConditionalIndexDefinition("unsafe.name", List.of("code"), false,
                List.of(new IndexCondition("deleted", IS_FALSE)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaticEntityTableMapper().toTable(WrongType.class)).hasMessageContaining("boolean");
        assertThatThrownBy(() -> new StaticEntityTableMapper().toTable(Protected.class)).hasMessageContaining("protected");
        assertThatThrownBy(() -> new StaticEntityTableMapper().toTable(Unknown.class)).hasMessageContaining("unknown");
        assertThatThrownBy(() -> new StaticEntityTableMapper().toTable(Duplicate.class)).hasMessageContaining("duplicate");
    }

    @Test void primaryKeyMayBeARecoveryIndexKey() {
        var table = new StaticEntityTableMapper().toTable(Plain.class);
        PlatformConditionalIndexes.add(table, new ConditionalIndexDefinition("ix_pending", List.of("id"), false,
                List.of(new IndexCondition("deleted", IS_FALSE))), field -> field);
        assertThat(table.getIndexes()).singleElement().satisfies(index -> assertThat(index.getColumns()).containsExactly("id"));
    }

    @Test void literalCodesAreDeterministicAndCaseSensitive() {
        var condition = new IndexCondition("state", IN_CODES, List.of("Ready", "READY", "Ready"));
        assertThat(condition.values()).containsExactly("READY", "Ready");
        assertThat(condition.matches("ready")).isFalse();
        assertThat(condition.matches(null)).isFalse();
        assertThat(condition.matches("Ready")).isTrue();
    }

    @Table(name = "plain_indexed") static class Plain extends StandardEntity {
        @Column(type = ColumnType.VARCHAR) String code;
    }
    @Table(name = "unique_indexed")
    @TenantUniqueConstraint(fields = "code")
    @ConditionalIndex(name = "ux_live", fields = "code", unique = true, when = @ConditionalIndex.Condition(field = "deleted", operator = IS_FALSE))
    @ConditionalIndex(name = "ux_retained", fields = "code", unique = true, when = @ConditionalIndex.Condition(field = "deleted", operator = IS_TRUE))
    static class Unique extends Plain { }
    @Table(name = "wrong_type_indexed")
    @ConditionalIndex(name = "ix_wrong", fields = "code", when = @ConditionalIndex.Condition(field = "code", operator = IS_TRUE))
    static class WrongType extends Plain { }
    @Table(name = "protected_indexed")
    @ConditionalIndex(name = "ix_protected", fields = "code", when = @ConditionalIndex.Condition(field = "secret", operator = IS_NULL))
    static class Protected extends Plain { @Column(type = ColumnType.VARCHAR) @EncryptedField String secret; }
    @Table(name = "unknown_indexed")
    @ConditionalIndex(name = "ix_unknown", fields = "code", when = @ConditionalIndex.Condition(field = "missing", operator = IS_NULL))
    static class Unknown extends Plain { }
    @Table(name = "duplicate_indexed")
    @ConditionalIndex(name = "ix_dupe", fields = "code", when = @ConditionalIndex.Condition(field = "deleted", operator = IS_TRUE))
    @ConditionalIndex(name = "ix_dupe", fields = "code", when = @ConditionalIndex.Condition(field = "deleted", operator = IS_FALSE))
    static class Duplicate extends Plain { }
}
