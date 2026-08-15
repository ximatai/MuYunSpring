package net.ximatai.muyun.spring.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ScopedTreeWebTest {
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldReadTreeChildrenInsideResolvedScope() {
        ScopedTreeService service = new ScopedTreeService();
        ScopedTreeController controller = new ScopedTreeController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();

        WebListResponse<?> response;
        try (TenantContext.Scope ignored = TenantContext.use("tenant_b")) {
            response = controller.tree(request, true);
        }

        assertThat(response.records()).hasSize(1);
        assertThat(service.childrenScope).isNotNull();
        assertThat(service.childrenParentId).isEqualTo(TreeAbility.ROOT_ID);
    }

    @Test
    void shouldMoveTreeInsideResolvedTenantScopeAndRestorePreviousContext() {
        ScopedTreeService service = new ScopedTreeService();
        ScopedTreeController controller = new ScopedTreeController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();

        try (TenantContext.Scope ignored = TenantContext.system("scoped tree maintenance")) {
            int response = controller.sort(request,
                    "moving", new TreeSortWebRequest("previous", null, TreeAbility.ROOT_ID));

            assertThat(response).isEqualTo(1);
            assertThat(service.moveTenantId).isEqualTo("tenant_b");
            assertThat(service.moveScope).isNotNull();
            assertThat(TenantContext.isSystem()).isTrue();
        }
    }

    @Test
    void shouldResolveTreeScopeFromServerSideExternalQueryValues() {
        ScopedTreeService service = new ScopedTreeService();
        ScopedTreeController controller = new ScopedTreeController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();

        try (TenantContext.Scope ignored = TenantContext.system("tree query test")) {
            controller.treeQuery(request, new WebQueryRequest(null, null, List.of(), null, java.util.Map.of(),
                    List.of(), null, null, java.util.Map.of("tenantId", "tenant_a"), null, null, List.of(), null));
        }

        assertThat(controller.observedTenantId).isEqualTo("tenant_a");
        assertThat(service.childrenParentId).isEqualTo(TreeAbility.ROOT_ID);
    }

    @Test
    void shouldNotTreatPostTreeQueryParametersAsExternalCriteria() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("tenantId", "forged-tenant");

        TreeWebQuerySupport.bind(request, new WebQueryRequest(null, null, List.of(), null, java.util.Map.of(),
                List.of(), null, null, java.util.Map.of(), null, null, List.of(), null));

        assertThat(TreeWebQuerySupport.externalQueryText(request, "tenantId")).isNull();
    }

    @Test
    void shouldPassTreeExternalScopeThroughDataScopeBeforeLoadingEveryTreeLevel() {
        DataScopedTreeService service = new DataScopedTreeService();
        DataScopedTreeController controller = new DataScopedTreeController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();

        try (TenantContext.Scope ignored = TenantContext.system("tree data-scope query test")) {
            controller.treeQuery(request, new WebQueryRequest(null, null, List.of(), null, java.util.Map.of(),
                    List.of(), null, null, java.util.Map.of("tenantId", "tenant_a"), null, null, List.of(), null));
        }

        assertThat(service.dataScope.lastInput).isNotNull();
        assertThat(service.listCriteria).isSameAs(service.dataScope.lastOutput);
        assertThat(service.listActions).containsOnly(PlatformAction.TREE);
    }

    private static final class ScopedTreeController extends WebSupport<ScopedTreeService>
            implements ScopedTreeWeb<ScopedTreeRecord, ScopedTreeService> {
        private String observedTenantId;
        private ScopedTreeController(ScopedTreeService service) {
            this.service = service;
        }

        @Override
        public TreeScope treeScope(HttpServletRequest request) {
            observedTenantId = TreeWebQuerySupport.externalQueryText(request, "tenantId");
            return TreeScope.tenant(Criteria.of().eq("tenantId", "tenant_b"), "tenant_b");
        }
    }

    private static final class ScopedTreeService extends AbstractAbilityService<ScopedTreeRecord>
            implements TreeAbility<ScopedTreeRecord> {
        private Criteria childrenScope;
        private String childrenParentId;
        private Criteria moveScope;
        private String moveTenantId;

        private ScopedTreeService() {
            super("test.scopedTree", ScopedTreeRecord.class, dao());
        }

        @Override
        public List<ScopedTreeRecord> children(Criteria scopeCriteria, String parentId) {
            childrenScope = scopeCriteria;
            childrenParentId = parentId;
            return List.of(record("root-1", TreeAbility.ROOT_ID));
        }

        @Override
        public void moveInTree(Criteria scopeCriteria, String id, String previousId, String nextId, String parentId) {
            moveScope = scopeCriteria;
            moveTenantId = TenantContext.currentTenantId().orElse(null);
        }
    }

    private static final class DataScopedTreeController extends WebSupport<DataScopedTreeService>
            implements ScopedTreeWeb<ScopedTreeRecord, DataScopedTreeService> {
        private DataScopedTreeController(DataScopedTreeService service) {
            this.service = service;
        }

        @Override
        public TreeScope treeScope(HttpServletRequest request) {
            return TreeScope.of(Criteria.of().eq("tenantId", TreeWebQuerySupport.externalQueryText(request, "tenantId")));
        }
    }

    private static final class DataScopedTreeService extends AbstractAbilityService<ScopedTreeRecord>
            implements TreeAbility<ScopedTreeRecord>, DataScopeAbility<ScopedTreeRecord> {
        private final CapturingDataScopeCriteriaService dataScope = new CapturingDataScopeCriteriaService();
        private Criteria listCriteria;
        private final java.util.ArrayList<PlatformAction> listActions = new java.util.ArrayList<>();
        private int listCalls;

        private DataScopedTreeService() {
            super("test.dataScopedTree", ScopedTreeRecord.class, dao());
        }

        @Override
        public DataScopeCriteriaService getDataScopeCriteriaService() {
            return dataScope;
        }

        @Override
        public List<ScopedTreeRecord> listForAction(PlatformAction action,
                                                    Criteria criteria,
                                                    PageRequest pageRequest,
                                                    Sort... sorts) {
            listActions.add(action);
            return DataScopeAbility.super.listForAction(action, criteria, pageRequest, sorts);
        }

        @Override
        public List<ScopedTreeRecord> list(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
            listCriteria = criteria;
            return listCalls++ == 0 ? List.of(record("root-1", TreeAbility.ROOT_ID)) : List.of();
        }
    }

    private static final class CapturingDataScopeCriteriaService implements DataScopeCriteriaService {
        private Criteria lastInput;
        private Criteria lastOutput;

        @Override
        public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                        String actionCode,
                                                        Criteria criteria,
                                                        Optional<CurrentUser> currentUser) {
            return resolve(criteria);
        }

        @Override
        public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                        ActionExecutionPolicy policy,
                                                        Criteria criteria,
                                                        Optional<CurrentUser> currentUser) {
            return resolve(criteria);
        }

        private DataScopeCriteriaResult resolve(Criteria criteria) {
            lastInput = criteria;
            // The data-scope service owns the final intersection. Use a fresh criteria here so
            // this test verifies delegation without reusing the mutable incoming criteria tree.
            lastOutput = Criteria.of().eq("authorized", true);
            return DataScopeCriteriaResult.restricted(lastOutput);
        }
    }

    @Getter
    @Setter
    private static final class ScopedTreeRecord extends StandardEntity implements TreeCapable {
        private String parentId;
        private Integer sortOrder;
    }

    private static ScopedTreeRecord record(String id, String parentId) {
        ScopedTreeRecord record = new ScopedTreeRecord();
        record.setId(id);
        record.setParentId(parentId);
        return record;
    }

    @SuppressWarnings("unchecked")
    private static BaseDao<ScopedTreeRecord, String> dao() {
        return mock(BaseDao.class);
    }
}
