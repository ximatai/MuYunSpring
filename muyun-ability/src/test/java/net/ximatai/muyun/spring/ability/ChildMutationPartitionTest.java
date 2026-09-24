package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ChildMutationPartitionTest {
    private final List<String> calls = new ArrayList<>();

    @AfterEach void reset() { PlatformAbilityRuntime.resetMutationTransactionOperator(); }

    @Test void globalHardDeleteChildLocksPersistedAndIncomingParentsBeforeBusinessValidation() {
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            @Override public <T> T execute(Supplier<T> work) { return work.get(); }
            @Override public void lock(String scope, String key) { calls.add("lock:" + key); }
        });
        var service = new Rows();
        var original = new Row();
        original.setParentId("z-parent");
        service.insert(original);
        calls.clear();
        // Separate request DTO: the persisted row, not the DTO, owns the previous parent.
        var changed = new Row();
        changed.setId(original.getId());
        changed.setVersion(original.getVersion());
        changed.setParentId("a-parent");
        service.update(changed);
        assertThat(calls).containsExactly("lock:a-parent", "lock:z-parent", "validate:z-parent->a-parent");
    }

    public static class Row extends StandardEntity {
        private String parentId;
        public String getParentId() { return parentId; }
        public void setParentId(String parentId) { this.parentId = parentId; }
    }

    private class Rows extends AbstractAbilityService<Row> implements ChildAbility<Row> {
        Rows() { super("test.child", Row.class, new InMemoryBaseDao<>()); }
        @Override public Function<Row, String> mutationParentKey() { return Row::getParentId; }
        @Override public void beforeUpdate(Row next, Row previous) {
            calls.add("validate:" + previous.getParentId() + "->" + next.getParentId());
        }
    }
}
