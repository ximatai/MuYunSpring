package net.ximatai.muyun.spring.ability.child;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.capability.SortCapable;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class BoundedChildReadContractTest {
    @Test void defaultReadersAreBoundedButCustomCollectionOrSortReadersRequireExplicitMigration() {
        assertThat(ChildAbility.supportsBoundedReads(DefaultReader.class)).isTrue();
        assertThat(ChildAbility.supportsBoundedReads(LegacyReader.class)).isFalse();
        assertThat(ChildAbility.supportsBoundedReads(LegacySortReader.class)).isFalse();
        assertThat(ChildAbility.supportsBoundedReads(MigratedSortReader.class)).isTrue();
        assertThat(ChildAbility.supportsBoundedReads(NewerLegacyOverride.class)).isFalse();
    }
    static class Row extends StandardEntity implements SortCapable {
        private Integer order;
        public Integer getSortOrder() { return order; }
        public void setSortOrder(Integer value) { order = value; }
    }
    static class DefaultReader implements ChildAbility<Row>, SortAbility<Row> {
        public String getModuleAlias() { return "test.child"; }
        public BaseDao<Row, String> getDao() { throw new UnsupportedOperationException(); }
    }
    static class LegacyReader extends DefaultReader {
        @Override public List<Row> selectChildRows(Criteria criteria) { return List.of(); }
    }
    static class LegacySortReader extends DefaultReader {
        @Override public List<Row> sortedList(Criteria criteria) { return List.of(); }
    }
    static class MigratedSortReader extends LegacySortReader {
        @Override public List<Row> selectChildRows(Criteria criteria, PageRequest page) { return List.of(); }
    }
    static class NewerLegacyOverride extends MigratedSortReader {
        @Override public List<Row> sortedList(Criteria criteria) { return List.of(); }
    }
}
