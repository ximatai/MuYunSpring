package net.ximatai.muyun.spring.ability.child;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AggregateChildMutationContextTest {
    static final class Row extends StandardEntity { }
    static class Child implements ChildAbility<Row> {
        final String alias;
        Child(String alias) { this.alias = alias; }
        public String getModuleAlias() { return alias; }
        public Class<Row> modelClass() { return Row.class; }
        public BaseDao<Row, String> getDao() { throw new UnsupportedOperationException(); }
    }
    static class Parent implements ChildrenAbility<Row> {
        final Child child; final String alias;
        Parent(String alias, Child child) { this.alias = alias; this.child = child; }
        public String getModuleAlias() { return alias; }
        public Class<Row> modelClass() { return Row.class; }
        public BaseDao<Row, String> getDao() { throw new UnsupportedOperationException(); }
        @Override public boolean usesAutomaticChildRelations() { return false; }
        @Override public List<ChildRelation<? extends net.ximatai.muyun.spring.common.model.contract.EntityContract, Row>> childRelations() {
            return List.of(new ChildRelation<>("lines", child, (row, id) -> {}, "parentId", row -> List.of()));
        }
    }
    @Test void nestedFramesRestoreTheirParentAndExceptionsAlwaysClearTheScope() throws Exception {
        var child = new Child("test.lines"); var parent = new Parent("test.parent", child);
        var before = new Row(); var current = new Row(); var inner = new Row();
        assertThat(AggregateChildMutationContext.parentOf(child)).isEmpty();
        try (var outer = AggregateChildMutationContext.open(parent, before, current)) {
            assertThat(AggregateChildMutationContext.parentOf(new Child("test.other"))).isEmpty();
            // Match registered model/module contracts even when services have different proxy identities.
            assertThat(AggregateChildMutationContext.parentOf(new Child("test.lines"))).get()
                    .satisfies(value -> { assertThat(value.before()).isSameAs(before); assertThat(value.current()).isSameAs(current); });
            assertThatThrownBy(() -> {
                try (var nested = AggregateChildMutationContext.open(parent, current, inner)) {
                    assertThat(AggregateChildMutationContext.parentOf(child).orElseThrow().current()).isSameAs(inner);
                    throw new IllegalStateException("failed child");
                }
            }).hasMessage("failed child");
            assertThat(AggregateChildMutationContext.parentOf(child).orElseThrow().current()).isSameAs(current);
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(() -> AggregateChildMutationContext.parentOf(child)).get()).isEmpty();
            }
        }
        assertThat(AggregateChildMutationContext.parentOf(child)).isEmpty();
    }
    @Test void standaloneRelationFramesMatchOnlyTheExactChildAndRestoreNestedScopes() throws Exception {
        var child = new Row(); child.setId("same-id");
        var unrelated = new Row(); unrelated.setId("same-id");
        assertThat(AggregateChildMutationContext.relationOf(child)).isEmpty();
        try (var outer = AggregateChildMutationContext.openRelation("test.parent", "lines", child)) {
            assertThat(AggregateChildMutationContext.relationOf(child)).get().satisfies(relation -> {
                assertThat(relation.moduleAlias()).isEqualTo("test.parent");
                assertThat(relation.relationCode()).isEqualTo("lines");
                assertThat(relation.child()).isSameAs(child);
            });
            assertThat(AggregateChildMutationContext.relationOf(unrelated)).isEmpty();
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(() -> AggregateChildMutationContext.relationOf(child)).get()).isEmpty();
            }
            assertThatThrownBy(() -> {
                try (var nested = AggregateChildMutationContext.openRelation("test.other", "items", unrelated)) {
                    assertThat(AggregateChildMutationContext.relationOf(child)).isEmpty();
                    assertThat(AggregateChildMutationContext.relationOf(unrelated).orElseThrow().moduleAlias()).isEqualTo("test.other");
                    throw new IllegalStateException("failed child");
                }
            }).hasMessage("failed child");
            assertThat(AggregateChildMutationContext.relationOf(child).orElseThrow().relationCode()).isEqualTo("lines");
            assertThat(AggregateChildMutationContext.relationOf(unrelated)).isEmpty();
        }
        assertThat(AggregateChildMutationContext.relationOf(child)).isEmpty();
        assertThat(AggregateChildMutationContext.relationOf(unrelated)).isEmpty();
    }

}
