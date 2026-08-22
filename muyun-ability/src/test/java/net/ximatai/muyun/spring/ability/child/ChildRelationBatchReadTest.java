package net.ximatai.muyun.spring.ability.child;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChildRelationBatchReadTest {
    @Test
    void shouldGroupOneChildQueryByTheDeclaredParentReader() {
        Child first = child("child-1", "parent-1");
        Child second = child("child-2", "parent-2");
        ChildAbility<Child> childAbility = new ChildAbility<>() {
            @Override public BaseDao<Child, String> getDao() { throw new UnsupportedOperationException(); }
            @Override public String getModuleAlias() { return "test.child"; }
            @Override public List<Child> selectChildRows(Criteria criteria) { return List.of(first, second); }
        };
        ChildRelation<Child, Parent> relation = new ChildRelation<>("children", childAbility,
                Child::setParentId, "parentId", Parent::getChildren, Child::getParentId);

        var grouped = relation.selectChildren(List.of("parent-2", "parent-1", "missing"));

        assertThat(grouped).containsOnlyKeys("parent-2", "parent-1", "missing");
        assertThat(grouped.get("parent-1")).containsExactly(first);
        assertThat(grouped.get("parent-2")).containsExactly(second);
        assertThat(grouped.get("missing")).isEmpty();
    }

    private static Child child(String id, String parentId) {
        Child value = new Child();
        value.setId(id);
        value.setParentId(parentId);
        return value;
    }

    private static final class Parent extends StandardEntity {
        private List<Child> children;
        List<Child> getChildren() { return children; }
    }

    private static final class Child extends StandardEntity {
        private String parentId;
        String getParentId() { return parentId; }
        void setParentId(String value) { parentId = value; }
    }
}
