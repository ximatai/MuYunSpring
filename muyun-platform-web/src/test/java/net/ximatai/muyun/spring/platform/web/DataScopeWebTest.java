package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.common.model.capability.EnabledCapable;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.model.capability.SortCapable;
import net.ximatai.muyun.spring.common.model.standard.StandardDataScopedEntity;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import net.ximatai.muyun.spring.common.security.MaskedField;
import net.ximatai.muyun.spring.common.security.SignedField;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Collection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DataScopeWebTest {
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        ActionExecutionContextHolder.clear();
    }

    @Test
    void crudWebShouldDelegateReadEndpointsToDataScopeAbilityWhenAvailable() {
        DataScopedCrudService service = new DataScopedCrudService();
        DataScopedCrudController controller = new DataScopedCrudController(service);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            WebPageResponse<DataScopedRecord> page = controller.query(null);
            DataScopedRecord viewed = controller.view("record-1");

            assertThat(page.records()).extracting(DataScopedRecord::getTitle).containsExactly("Query");
            assertThat(viewed.getTitle()).isEqualTo("View record-1");
            assertThat(service.queryAction).isEqualTo(PlatformAction.QUERY);
            assertThat(service.viewAction).isEqualTo(PlatformAction.VIEW);
        }
    }

    @Test
    void staticQueryViewWebShouldDelegateProjectionFallbackToDataScopeAbility() {
        DataScopedCrudService service = new DataScopedCrudService();
        DataScopedStaticQueryViewController controller = new DataScopedStaticQueryViewController(service);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            WebPageResponse<DataScopedRecord> page = controller.query(null);

            assertThat(page.records()).extracting(DataScopedRecord::getTitle).containsExactly("Query");
            assertThat(service.queryAction).isEqualTo(PlatformAction.QUERY);
        }
    }

    @Test
    void crudWebShouldRejectUnpagedQueryUnlessControllerOptsIn() {
        DataScopedCrudController controller = new DataScopedCrudController(new DataScopedCrudService());

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> controller.query(unpagedRequest(false)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unpaged query is not supported");
        }
    }

    @Test
    void crudWebShouldDelegateUnpagedQueryOnlyAfterExplicitOptIn() {
        DataScopedCrudService service = new DataScopedCrudService();
        UnpagedDataScopedCrudController controller = new UnpagedDataScopedCrudController(service);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            WebPageResponse<DataScopedRecord> page = controller.query(unpagedRequest(false));

            assertThat(page.records()).extracting(DataScopedRecord::getTitle).containsExactly("List");
            assertThat(service.queryAction).isEqualTo(PlatformAction.QUERY);
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> controller.query(unpagedRequest(true)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unpaged query navigation is not supported");
        }
    }

    @Test
    void crudWebShouldRequireDataScopeBeforeRecordMutationWhenAvailable() {
        DataScopedCrudService service = new DataScopedCrudService();
        DataScopedCrudController controller = new DataScopedCrudController(service);
        DataScopedRecord record = new DataScopedRecord();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            controller.update("record-1", record);
            controller.delete("record-2", new RecordActionWebRequest(0));
        }

        assertThat(service.scopedActions).containsExactly(PlatformAction.UPDATE, PlatformAction.DELETE);
        assertThat(service.scopedIdCalls).containsExactly(List.of("record-1"), List.of("record-2"));
        assertThat(service.scopedDataAuth).containsExactly(true, true);
        assertThat(service.selectedIds).containsExactly("record-2");
        assertThat(service.viewAction).isEqualTo(PlatformAction.VIEW);
    }

    @Test
    void crudWebShouldUseCurrentActionPolicyForDataScopeMutationCheck() {
        DataScopedCrudService service = new DataScopedCrudService();
        DataScopedCrudController controller = new DataScopedCrudController(service);
        DataScopedRecord record = new DataScopedRecord();
        ActionExecutionPolicy actionPolicy = new ActionExecutionPolicy("update", PlatformActionLevel.RECORD,
                ActionAccessMode.AUTH_REQUIRED, true, false, ActionDefaultGrantPolicy.NONE, null);
        ActionExecutionContext context = ActionExecutionContext.ofPolicy(
                service.getModuleAlias(), actionPolicy, java.util.Set.of("record-1"), java.util.Optional.empty());

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             ActionExecutionContextHolder.Scope action = ActionExecutionContextHolder.use(context)) {
            controller.update("record-1", record);
        }

        assertThat(service.scopedActions).containsExactly(PlatformAction.UPDATE);
        assertThat(service.scopedDataAuth).containsExactly(false);
    }

    @Test
    void crudWebShouldMaskProtectedStaticFieldsBeforeOutput() {
        ProtectedWebService service = new ProtectedWebService();
        ProtectedWebController controller = new ProtectedWebController(service);

        WebPageResponse<ProtectedWebRecord> page;
        ProtectedWebRecord viewed;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            page = controller.query(null);
            viewed = controller.view("protected-1");
        }

        assertThat(page.records().getFirst().getPhone()).isEqualTo("138****5678");
        assertThat(page.records().getFirst().getPhoneSignature()).isNull();
        assertThat(viewed.getPhone()).isEqualTo("138****5678");
        assertThat(viewed.getPhoneSignature()).isNull();
    }

    @Test
    void enableWebShouldRequireDataScopeBeforeRecordMutationWhenAvailable() {
        DataScopedEnabledService service = new DataScopedEnabledService();
        DataScopedEnabledController controller = new DataScopedEnabledController(service);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            controller.enable("record-1", new RecordActionWebRequest(0));
            controller.disable("record-2", new RecordActionWebRequest(0));
        }

        assertThat(service.scopedActions).containsExactly(PlatformAction.ENABLE, PlatformAction.DISABLE);
        assertThat(service.scopedIdCalls).containsExactly(List.of("record-1"), List.of("record-2"));
    }

    @Test
    void enableWebShouldUseResolvedExistingRecordTenantForSystemMutation() {
        DataScopedEnabledService service = new DataScopedEnabledService();
        DataScopedEnabledController controller = new DataScopedEnabledController(service);

        try (TenantContext.Scope ignored = TenantContext.system("test")) {
            controller.enable("record-1", new RecordActionWebRequest(0));
        }

        assertThat(service.mutationTenantIds).containsExactly(Optional.of("tenant-b"));
    }

    @Test
    void treeWebShouldDelegateReadEndpointsToDataScopeAbilityWhenAvailable() {
        DataScopedTreeService service = new DataScopedTreeService();
        DataScopedTreeController controller = new DataScopedTreeController(service);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            WebListResponse<?> tree = controller.tree(request, true);
            WebListResponse<?> subtree = controller.tree(request, "root-1", true, true);

            assertThat(tree.records()).hasSize(2);
            assertThat(subtree.records()).hasSize(2);
            assertThat(service.childrenActions).containsOnly(PlatformAction.TREE);
            assertThat(service.viewAction).isEqualTo(PlatformAction.TREE);
        }
    }

    @Test
    void sortWebShouldRequireFullSortScopeWhenDataScopeAbilityIsAvailable() {
        DataScopedSortService service = new DataScopedSortService();
        DataScopedSortController controller = new DataScopedSortController(service);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            controller.sort("moving", new SortWebRequest("previous", null));
        }

        assertThat(service.scopedIdCalls).anySatisfy(ids ->
                assertThat(ids).containsExactly("moving", "previous", "hidden"));
    }

    @Test
    void treeWebShouldRequireFullTreeSortScopeWhenDataScopeAbilityIsAvailable() {
        DataScopedTreeService service = new DataScopedTreeService();
        DataScopedTreeController controller = new DataScopedTreeController(service);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            controller.sort(new MockHttpServletRequest(), "moving", new TreeSortWebRequest("previous", null, "parent"));
        }

        assertThat(service.scopedIdCalls).anySatisfy(ids ->
                assertThat(ids).containsExactlyInAnyOrder("moving", "previous", "parent", "hidden"));
    }

    @Getter
    @Setter
    private static class DataScopedRecord extends StandardDataScopedEntity {
        private String title;
    }

    @Getter
    @Setter
    private static final class DataScopedTreeRecord extends DataScopedRecord implements TreeCapable {
        private String parentId;
        private Integer sortOrder;
    }

    @Getter
    @Setter
    private static final class DataScopedSortRecord extends DataScopedRecord implements SortCapable {
        private Integer sortOrder;
    }

    @Getter
    @Setter
    private static final class DataScopedEnabledRecord extends DataScopedRecord implements EnabledCapable {
        private Boolean enabled;
    }

    @Getter
    @Setter
    private static final class ProtectedWebRecord extends DataScopedRecord {
        @SignedField
        @MaskedField(FieldMaskingPolicy.PHONE)
        private String phone;
        private String phoneSignature;
    }

    private static final class DataScopedCrudService extends AbstractAbilityService<DataScopedRecord>
            implements DataScopeAbility<DataScopedRecord> {
        private final java.util.ArrayList<PlatformAction> scopedActions = new java.util.ArrayList<>();
        private final java.util.ArrayList<List<String>> scopedIdCalls = new java.util.ArrayList<>();
        private final java.util.ArrayList<Boolean> scopedDataAuth = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> selectedIds = new java.util.ArrayList<>();
        private PlatformAction queryAction;
        private PlatformAction viewAction;

        private DataScopedCrudService() {
            super("demo.dataScopedCrud", DataScopedRecord.class, dao());
        }

        @Override
        public DataScopeCriteriaService getDataScopeCriteriaService() {
            return new AllowAllDataScopeCriteriaService();
        }

        @Override
        public PageResult<DataScopedRecord> pageQueryForAction(PlatformAction action,
                                                               Criteria criteria,
                                                               PageRequest pageRequest,
                                                               Sort... sorts) {
            queryAction = action;
            DataScopedRecord record = new DataScopedRecord();
            record.setTitle("Query");
            return PageResult.of(List.of(record), 1, pageRequest);
        }

        @Override
        public List<DataScopedRecord> listForAction(PlatformAction action, Criteria criteria, Sort... sorts) {
            queryAction = action;
            DataScopedRecord record = new DataScopedRecord();
            record.setTitle("List");
            return List.of(record);
        }

        @Override
        public DataScopedRecord selectForAction(PlatformAction action, String id) {
            viewAction = action;
            DataScopedRecord record = new DataScopedRecord();
            record.setTitle("View " + id);
            return record;
        }

        @Override
        public DataScopedRecord select(String id) {
            selectedIds.add(id);
            DataScopedRecord record = new DataScopedRecord();
            record.setId(id);
            record.setTitle("Select " + id);
            return record;
        }

        @Override
        public DataScopeCriteriaResult requireRecordScopeResult(ActionExecutionPolicy policy, Collection<String> ids) {
            scopedActions.add(PlatformAction.fromCode(policy.actionCode()).orElseThrow());
            scopedIdCalls.add(List.copyOf(ids));
            scopedDataAuth.add(policy.requiresDataScope());
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }

        @Override
        public int update(DataScopedRecord record) {
            return 1;
        }

        @Override
        public int delete(String id, Integer expectedVersion) {
            return 1;
        }
    }

    private static final class DataScopedEnabledService extends AbstractAbilityService<DataScopedEnabledRecord>
            implements EnableAbility<DataScopedEnabledRecord>, DataScopeAbility<DataScopedEnabledRecord> {
        private final java.util.ArrayList<PlatformAction> scopedActions = new java.util.ArrayList<>();
        private final java.util.ArrayList<List<String>> scopedIdCalls = new java.util.ArrayList<>();
        private final java.util.ArrayList<Optional<String>> mutationTenantIds = new java.util.ArrayList<>();

        private DataScopedEnabledService() {
            super("demo.dataScopedEnabled", DataScopedEnabledRecord.class, dao());
        }

        @Override
        public DataScopeCriteriaService getDataScopeCriteriaService() {
            return new AllowAllDataScopeCriteriaService();
        }

        @Override
        public DataScopeCriteriaResult requireRecordScopeResult(ActionExecutionPolicy policy, Collection<String> ids) {
            scopedActions.add(PlatformAction.fromCode(policy.actionCode()).orElseThrow());
            scopedIdCalls.add(List.copyOf(ids));
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }

        @Override
        public int enable(String id, Integer expectedVersion) {
            mutationTenantIds.add(TenantContext.currentTenantId());
            return 1;
        }

        @Override
        public int disable(String id, Integer expectedVersion) {
            mutationTenantIds.add(TenantContext.currentTenantId());
            return 1;
        }

        @Override
        public DataScopedEnabledRecord select(String id) {
            DataScopedEnabledRecord record = new DataScopedEnabledRecord();
            record.setId(id);
            record.setTitle("Enabled " + id);
            record.setTenantId("tenant-b");
            return record;
        }
    }

    private static final class DataScopedTreeService extends AbstractAbilityService<DataScopedTreeRecord>
            implements TreeAbility<DataScopedTreeRecord>, DataScopeAbility<DataScopedTreeRecord> {
        private final java.util.ArrayList<PlatformAction> childrenActions = new java.util.ArrayList<>();
        private final java.util.ArrayList<List<String>> scopedIdCalls = new java.util.ArrayList<>();
        private PlatformAction viewAction;

        private DataScopedTreeService() {
            super("demo.dataScopedTree", DataScopedTreeRecord.class, dao());
        }

        @Override
        public DataScopeCriteriaService getDataScopeCriteriaService() {
            return new AllowAllDataScopeCriteriaService();
        }

        @Override
        public DataScopedTreeRecord selectForAction(PlatformAction action, String id) {
            viewAction = action;
            return treeRecord(id, "Root", TreeAbility.ROOT_ID);
        }

        @Override
        public List<DataScopedTreeRecord> childrenForAction(PlatformAction action, String parentId) {
            childrenActions.add(action);
            if (TreeAbility.ROOT_ID.equals(parentId)) {
                return List.of(treeRecord("root-1", "Root", TreeAbility.ROOT_ID));
            }
            if ("root-1".equals(parentId)) {
                return List.of(treeRecord("child-1", "Child", parentId));
            }
            return List.of();
        }

        @Override
        public DataScopeCriteriaResult requireRecordScopeResult(ActionExecutionPolicy policy, Collection<String> ids) {
            scopedIdCalls.add(List.copyOf(ids));
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }

        @Override
        public DataScopedTreeRecord select(String id) {
            return switch (id) {
                case "moving" -> treeRecord("moving", "Moving", "parent");
                case "previous" -> treeRecord("previous", "Previous", "parent");
                case "parent" -> treeRecord("parent", "Parent", TreeAbility.ROOT_ID);
                default -> null;
            };
        }

        @Override
        public List<DataScopedTreeRecord> children(String parentId) {
            if ("parent".equals(parentId)) {
                return List.of(
                        treeRecord("moving", "Moving", "parent"),
                        treeRecord("previous", "Previous", "parent"),
                        treeRecord("hidden", "Hidden", "parent")
                );
            }
            return List.of();
        }
    }

    private static final class DataScopedSortService extends AbstractAbilityService<DataScopedSortRecord>
            implements SortAbility<DataScopedSortRecord>, DataScopeAbility<DataScopedSortRecord> {
        private final java.util.ArrayList<List<String>> scopedIdCalls = new java.util.ArrayList<>();

        private DataScopedSortService() {
            super("demo.dataScopedSort", DataScopedSortRecord.class, dao());
        }

        @Override
        public DataScopeCriteriaService getDataScopeCriteriaService() {
            return new AllowAllDataScopeCriteriaService();
        }

        @Override
        public DataScopeCriteriaResult requireRecordScopeResult(ActionExecutionPolicy policy, Collection<String> ids) {
            scopedIdCalls.add(List.copyOf(ids));
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }

        @Override
        public DataScopedSortRecord select(String id) {
            return sortRecord(id);
        }

        @Override
        public List<DataScopedSortRecord> sortedList(Criteria criteria) {
            return List.of(sortRecord("moving"), sortRecord("previous"), sortRecord("hidden"));
        }
    }

    private static final class ProtectedWebService extends AbstractAbilityService<ProtectedWebRecord>
            implements FieldProtectionAbility<ProtectedWebRecord> {
        private ProtectedWebService() {
            super("demo.protectedWeb", ProtectedWebRecord.class, dao());
        }

        @Override
        public PageResult<ProtectedWebRecord> pageQuery(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
            return PageResult.of(List.of(record("query")), 1, pageRequest);
        }

        @Override
        public ProtectedWebRecord select(String id) {
            return record(id);
        }

        private ProtectedWebRecord record(String id) {
            ProtectedWebRecord record = new ProtectedWebRecord();
            record.setId(id);
            record.setPhone("13812345678");
            record.setPhoneSignature("sig:phone:13812345678");
            return record;
        }
    }

    private static final class DataScopedCrudController extends WebSupport<DataScopedCrudService>
            implements CrudWeb<DataScopedRecord, DataScopedCrudService> {
        private DataScopedCrudController(DataScopedCrudService service) {
            this.service = service;
        }
    }

    /** No projection service is supplied: this exercises StaticQueryViewWeb's query fallback. */
    private static final class DataScopedStaticQueryViewController extends WebSupport<DataScopedCrudService>
            implements StaticQueryViewWeb<DataScopedRecord, DataScopedCrudService>, StaticModuleUiContributor {
        private DataScopedStaticQueryViewController(DataScopedCrudService service) {
            this.service = service;
        }

        @Override
        public ModuleUiDefinition moduleUiDefinition() {
            return ModuleUiDefinition.builder("demo.data_scoped_crud")
                    .page(PageTemplates.treeManagement(page -> page
                            .detail(detail -> detail.display(display -> display.field("title")))))
                    .build();
        }
    }

    private static final class UnpagedDataScopedCrudController extends WebSupport<DataScopedCrudService>
            implements CrudWeb<DataScopedRecord, DataScopedCrudService> {
        private UnpagedDataScopedCrudController(DataScopedCrudService service) {
            this.service = service;
        }

        @Override
        public boolean supportsUnpagedQuery() {
            return true;
        }
    }

    private static final class DataScopedEnabledController extends WebSupport<DataScopedEnabledService>
            implements EnableWeb<DataScopedEnabledRecord, DataScopedEnabledService>,
            MutationTenantScopeResolver<DataScopedEnabledRecord> {
        private DataScopedEnabledController(DataScopedEnabledService service) {
            this.service = service;
        }

        @Override
        public Optional<String> tenantIdForExistingRecord(String id) {
            return Optional.of("tenant-b");
        }
    }

    private static final class DataScopedTreeController extends WebSupport<DataScopedTreeService>
            implements TreeWeb<DataScopedTreeRecord, DataScopedTreeService> {
        private DataScopedTreeController(DataScopedTreeService service) {
            this.service = service;
        }
    }

    private static final class DataScopedSortController extends WebSupport<DataScopedSortService>
            implements SortWeb<DataScopedSortRecord, DataScopedSortService> {
        private DataScopedSortController(DataScopedSortService service) {
            this.service = service;
        }
    }

    private static final class ProtectedWebController extends WebSupport<ProtectedWebService>
            implements CrudWeb<ProtectedWebRecord, ProtectedWebService> {
        private ProtectedWebController(ProtectedWebService service) {
            this.service = service;
        }
    }

    private static DataScopedTreeRecord treeRecord(String id, String title, String parentId) {
        DataScopedTreeRecord record = new DataScopedTreeRecord();
        record.setId(id);
        record.setTitle(title);
        record.setParentId(parentId);
        return record;
    }

    private static DataScopedSortRecord sortRecord(String id) {
        DataScopedSortRecord record = new DataScopedSortRecord();
        record.setId(id);
        record.setTitle(id);
        record.setSortOrder(100);
        return record;
    }

    private static WebQueryRequest unpagedRequest(boolean navigationSession) {
        return new WebQueryRequest(null, true, List.of(), null, java.util.Map.of(), List.of(),
                null, null, java.util.Map.of(), navigationSession, null, List.of(), null);
    }

    @SuppressWarnings("unchecked")
    private static <T extends net.ximatai.muyun.spring.common.model.contract.EntityContract> BaseDao<T, String> dao() {
        return mock(BaseDao.class);
    }
}
