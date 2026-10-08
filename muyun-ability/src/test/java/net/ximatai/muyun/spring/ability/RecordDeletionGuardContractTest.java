package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

class RecordDeletionGuardContractTest {
    @AfterEach void reset() { PlatformAbilityRuntime.resetRecordDeletionGuard(); }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sharedDeleteKeepsParentCollectionLocksAheadOfDomainGuards(boolean soft) {
        var dao = new InMemoryBaseDao<Record>();
        var record = new Record(); record.setId("child"); record.setVersion(1);
        dao.insert(record);
        var order = new ArrayList<String>();
        var service = soft ? new SoftService(dao, order) : new HardService(dao, order);
        PlatformAbilityRuntime.configureRecordDeletionGuard((ability, stored) -> {
            assertThat(order).containsExactly("parent:parent");
            assertThat(stored).isSameAs(record);
            throw new IllegalStateException("record lifecycle is active");
        });
        assertThatThrownBy(() -> service.delete("child", 1)).hasMessage("record lifecycle is active");
        assertThat(order).containsExactly("parent:parent");
        assertThat(dao.findById("child")).isSameAs(record);
        assertThat(record.getVersion()).isEqualTo(1);
        assertThat(record.getDeleted()).isFalse();
    }
    static class Record extends StandardEntity {}
    static class HardService extends AbstractAbilityService<Record> implements ChildAbility<Record> {
        final List<String> order;
        HardService(BaseDao<Record, String> dao, List<String> order) { super("test.child", Record.class, dao); this.order = order; }
        @Override public Function<Record, String> mutationParentKey() { return record -> "parent"; }
        @Override public void lockParentMutation(String id) { order.add("parent:" + id); }
        @Override public void beforeDelete(String id) { order.add("beforeDelete"); }
    }
    static class SoftService extends HardService implements SoftDeleteAbility<Record> {
        SoftService(BaseDao<Record, String> dao, List<String> order) { super(dao, order); }
    }
}
