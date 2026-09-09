package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.standard.StandardDataScopedEntity;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.web.WebSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CrudWebAggregateChildMutationGuardTest {
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldRejectForgedAuditFieldsOnNewAggregateChildren() {
        AggregateParentService service = new AggregateParentService();
        AggregateParentController controller = new AggregateParentController(service);
        AggregateChild child = new AggregateChild();
        child.setCreatedBy("forged-user");
        AggregateParent record = parent(null, List.of(child));

        try (TenantContext.Scope ignored = TenantContext.system("test aggregate form mutation")) {
            assertThatThrownBy(() -> controller.insert(record)).hasMessageContaining("平台审计字段不可修改");
        }
        assertThat(service.persisted).isNull();
    }

    @Test
    void shouldRejectChangedAuditOrPermissionFieldsOnExistingAggregateChildren() {
        AggregateParentService service = new AggregateParentService();
        service.persisted = persistedParent();
        AggregateParentController controller = new AggregateParentController(service);

        AggregateChild changedAudit = child("child-1");
        changedAudit.setCreatedBy("other-user");
        AggregateParent auditEdit = parent("parent-1", List.of(changedAudit));
        AggregateChild changedPermission = child("child-1");
        changedPermission.setAuthUserId("other-user");
        AggregateParent permissionEdit = parent("parent-1", List.of(changedPermission));

        try (TenantContext.Scope ignored = TenantContext.system("test aggregate form mutation")) {
            assertThatThrownBy(() -> controller.update("parent-1", auditEdit))
                    .hasMessageContaining("平台审计字段不可修改");
            assertThatThrownBy(() -> controller.update("parent-1", permissionEdit))
                    .hasMessageContaining("请通过授权动作变更权限");
        }
        assertThat(service.persisted.getChildren().getFirst().getCreatedBy()).isEqualTo("owner-1");
        assertThat(service.persisted.getChildren().getFirst().getAuthUserId()).isEqualTo("owner-1");
    }

    @Test
    void shouldPreserveEchoedAndOmittedInternalFieldsForAggregateChildrenAndAllowLegitimateSave() {
        AggregateParentService service = new AggregateParentService();
        service.persisted = persistedParent();
        AggregateParentController controller = new AggregateParentController(service);
        AggregateChild echoed = child("child-1");
        echoed.setCreatedBy("owner-1");
        echoed.setCreatedAt(Instant.parse("2026-09-08T01:00:00Z"));
        echoed.setAuthUserId("owner-1");
        AggregateChild omitted = child("child-2");
        AggregateParent update = parent("parent-1", List.of(echoed, omitted));

        try (TenantContext.Scope ignored = TenantContext.system("test aggregate form mutation")) {
            assertThatCode(() -> controller.update("parent-1", update)).doesNotThrowAnyException();
            assertThatCode(() -> controller.insert(parent(null, List.of(new AggregateChild())))).doesNotThrowAnyException();
        }

        assertThat(echoed.getCreatedBy()).isEqualTo("owner-1");
        assertThat(echoed.getCreatedAt()).isEqualTo(Instant.parse("2026-09-08T01:00:00Z"));
        assertThat(echoed.getAuthUserId()).isEqualTo("owner-1");
        assertThat(omitted.getCreatedBy()).isEqualTo("owner-2");
        assertThat(omitted.getCreatedAt()).isEqualTo(Instant.parse("2026-09-08T02:00:00Z"));
        assertThat(omitted.getAuthUserId()).isEqualTo("owner-2");
    }

    private static AggregateParent persistedParent() {
        AggregateChild first = child("child-1");
        first.setCreatedBy("owner-1");
        first.setCreatedAt(Instant.parse("2026-09-08T01:00:00Z"));
        first.setAuthUserId("owner-1");
        AggregateChild second = child("child-2");
        second.setCreatedBy("owner-2");
        second.setCreatedAt(Instant.parse("2026-09-08T02:00:00Z"));
        second.setAuthUserId("owner-2");
        return parent("parent-1", List.of(first, second));
    }

    private static AggregateParent parent(String id, List<AggregateChild> children) {
        AggregateParent parent = new AggregateParent();
        parent.setId(id);
        parent.setChildren(children);
        return parent;
    }

    private static AggregateChild child(String id) {
        AggregateChild child = new AggregateChild();
        child.setId(id);
        return child;
    }

    private static final class AggregateParentController extends WebSupport<AggregateParentService>
            implements CrudWeb<AggregateParent, AggregateParentService> {
        private AggregateParentController(AggregateParentService service) {
            this.service = service;
        }
    }

    private static final class AggregateParentService extends AbstractAbilityService<AggregateParent>
            implements ChildrenAbility<AggregateParent> {
        private final ChildAbility<AggregateChild> childAbility = new ChildAbility<>() {
            @Override
            public BaseDao<AggregateChild, String> getDao() {
                return dao();
            }

            @Override
            public String getModuleAlias() {
                return "test.aggregate-child";
            }

            @Override
            public List<AggregateChild> selectChildRows(Criteria criteria) {
                return persisted == null || persisted.getChildren() == null ? List.of() : persisted.getChildren();
            }
        };
        private final ChildRelation<AggregateChild, AggregateParent> relation = new ChildRelation<>(
                "children", childAbility, (child, parentId) -> child.setParentId(parentId), "parentId",
                AggregateParent::getChildren);
        private AggregateParent persisted;

        private AggregateParentService() {
            super("test.aggregate-parent", AggregateParent.class, dao());
        }

        @Override
        public List<ChildRelation<? extends EntityContract, AggregateParent>> childRelations() {
            return List.of(relation);
        }

        @Override
        public boolean usesAutomaticChildRelations() {
            return false;
        }

        @Override
        public AggregateParent select(String id) {
            return persisted;
        }

        @Override
        public String insert(AggregateParent record) {
            record.setId("parent-created");
            persisted = record;
            return record.getId();
        }

        @Override
        public int update(AggregateParent record) {
            persisted = record;
            return 1;
        }
    }

    private static final class AggregateParent extends StandardEntity {
        private List<AggregateChild> children;

        public List<AggregateChild> getChildren() {
            return children;
        }

        public void setChildren(List<AggregateChild> children) {
            this.children = children;
        }
    }

    private static final class AggregateChild extends StandardDataScopedEntity {
        private String parentId;

        public void setParentId(String parentId) { this.parentId = parentId; }
    }

    @SuppressWarnings("unchecked")
    private static <T extends StandardEntity> BaseDao<T, String> dao() {
        return mock(BaseDao.class);
    }
}
