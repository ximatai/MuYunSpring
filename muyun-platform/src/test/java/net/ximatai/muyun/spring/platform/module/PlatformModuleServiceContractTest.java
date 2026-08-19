package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformModuleServiceContractTest {
    @Test
    void shouldRequireModuleAliasBeforeIdGeneration() {
        PlatformModuleService service = new PlatformModuleService(new ModuleMemoryDao());
        PlatformModule module = new PlatformModule();
        module.setApplicationAlias("crm");
        module.setTitle("Customer");

        assertThatThrownBy(() -> service.insert(module))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("moduleAlias");
    }

    @Test
    void shouldUseAliasAsModuleIdAndFillTreeDefaults() {
        PlatformModuleService service = new PlatformModuleService(new ModuleMemoryDao());
        PlatformModule module = module("crm.customer", "crm");

        String id = service.insert(module);

        assertThat(id).isEqualTo("crm.customer");
        assertThat(module.getId()).isEqualTo("crm.customer");
        assertThat(module.getParentId()).isEqualTo(TreeAbility.ROOT_ID);
        assertThat(module.getEnabled()).isTrue();
        assertThat(module.getModuleKind()).isEqualTo(ModuleKind.STATIC);
    }

    @Test
    void shouldResolveGlobalModuleFromTenantContext() {
        PlatformModuleService service = new PlatformModuleService(new ModuleMemoryDao());
        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            service.insert(module("crm.customer", "crm"));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.select("crm.customer")).isNull();
            assertThat(service.resolveVisibleModule("crm.customer")).isNotNull();
        }
    }

    @Test
    void shouldResolveTenantPrivateModuleWithoutLeakingToOtherTenants() {
        PlatformModuleService service = new PlatformModuleService(new ModuleMemoryDao());

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            service.insert(module("crm.tenant_customer", "crm"));
            assertThat(service.resolveVisibleModule("crm.tenant_customer")).isNotNull();
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            assertThat(service.resolveVisibleModule("crm.tenant_customer")).isNull();
        }
    }

    @Test
    void shouldListGlobalAndTenantVisibleModulesWithoutLeakingOtherTenantModules() {
        PlatformModuleService service = new PlatformModuleService(new ModuleMemoryDao());
        try (TenantContext.Scope ignored = TenantContext.system("create global module")) {
            service.insert(module("crm.customer", "crm"));
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            PlatformModule tenantModule = module("crm.tenant_customer", "crm");
            tenantModule.setTenantId("tenant-a");
            service.insert(tenantModule);
            assertThat(service.listVisibleModules()).extracting(PlatformModule::getAlias)
                    .containsExactly("crm.customer", "crm.tenant_customer");
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            assertThat(service.listVisibleModules()).extracting(PlatformModule::getAlias)
                    .containsExactly("crm.customer");
        }
    }

    @Test
    void shouldListCapturedTenantModulesWhileCallerIsInSystemScope() {
        PlatformModuleService service = new PlatformModuleService(new ModuleMemoryDao());
        try (TenantContext.Scope ignored = TenantContext.system("create global module")) {
            service.insert(module("crm.customer", "crm"));
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            PlatformModule tenantModule = module("crm.tenant_customer", "crm");
            tenantModule.setTenantId("tenant-a");
            service.insert(tenantModule);
        }

        try (TenantContext.Scope ignored = TenantContext.system("openapi catalog")) {
            assertThat(service.listVisibleModules("tenant-a")).extracting(PlatformModule::getAlias)
                    .containsExactly("crm.customer", "crm.tenant_customer");
            assertThat(service.listVisibleModules(null)).extracting(PlatformModule::getAlias)
                    .containsExactly("crm.customer");
        }
    }

    @Test
    void shouldRejectModuleAliasOutsideApplication() {
        PlatformModuleService service = new PlatformModuleService(new ModuleMemoryDao());
        PlatformModule module = module("sales.customer", "crm");

        assertThatThrownBy(() -> service.insert(module))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moduleAlias");
    }

    @Test
    void shouldSupportModuleTreeWithinSameApplication() {
        ModuleMemoryDao dao = new ModuleMemoryDao();
        PlatformModuleService service = new PlatformModuleService(dao);
        service.insert(module("crm.customer", "crm"));
        PlatformModule child = module("crm.customer.profile", "crm");
        child.setParentId("crm.customer");

        service.insert(child);

        assertThat(service.children("crm.customer"))
                .extracting(PlatformModule::getAlias)
                .containsExactly("crm.customer.profile");
    }

    @Test
    void shouldResolveRootModulesByApplication() {
        ModuleMemoryDao dao = new ModuleMemoryDao();
        PlatformModuleService service = new PlatformModuleService(dao);
        service.insert(module("crm.customer", "crm"));
        service.insert(module("sales.contract", "sales"));

        assertThat(service.rootModules("crm"))
                .extracting(PlatformModule::getAlias)
                .containsExactly("crm.customer");
    }

    @Test
    void shouldRejectUnscopedRootChildrenLookup() {
        PlatformModuleService service = new PlatformModuleService(new ModuleMemoryDao());

        assertThatThrownBy(() -> service.children(TreeAbility.ROOT_ID))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("rootModules");
    }

    @Test
    void shouldResolveChildrenByApplication() {
        ModuleMemoryDao dao = new ModuleMemoryDao();
        PlatformModuleService service = new PlatformModuleService(dao);
        service.insert(module("crm.customer", "crm"));
        PlatformModule crmChild = module("crm.customer.profile", "crm");
        crmChild.setParentId("crm.customer");
        service.insert(crmChild);

        assertThat(service.children("sales", "crm.customer")).isEmpty();
        assertThat(service.children("crm", "crm.customer"))
                .extracting(PlatformModule::getAlias)
                .containsExactly("crm.customer.profile");
    }

    @Test
    void shouldRejectModuleTreeAcrossApplications() {
        ModuleMemoryDao dao = new ModuleMemoryDao();
        PlatformModuleService service = new PlatformModuleService(dao);
        service.insert(module("crm.customer", "crm"));
        PlatformModule child = module("sales.contract", "sales");
        child.setParentId("crm.customer");

        assertThatThrownBy(() -> service.insert(child))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same application");
    }

    @Test
    void shouldReorderModulesWithinSameApplicationAndParent() {
        ModuleMemoryDao dao = new ModuleMemoryDao();
        PlatformModuleService service = new PlatformModuleService(dao);
        service.insert(module("crm.customer", "crm"));
        service.insert(module("crm.contract", "crm"));

        service.reorder(List.of("crm.contract", "crm.customer"));

        assertThat(service.rootModules("crm"))
                .extracting(PlatformModule::getAlias)
                .containsExactly("crm.contract", "crm.customer");
    }

    @Test
    void shouldRejectReorderAcrossApplicationOrParent() {
        ModuleMemoryDao dao = new ModuleMemoryDao();
        PlatformModuleService service = new PlatformModuleService(dao);
        service.insert(module("crm.customer", "crm"));
        service.insert(module("sales.contract", "sales"));
        PlatformModule child = module("crm.customer.profile", "crm");
        child.setParentId("crm.customer");
        service.insert(child);

        assertThatThrownBy(() -> service.reorder(List.of("crm.customer", "sales.contract")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same application");
        assertThatThrownBy(() -> service.reorder(List.of("crm.customer", "crm.customer.profile")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same parent");
    }

    @Test
    void shouldProtectSystemManagedModuleFromOrdinaryMutation() {
        PlatformModuleService service = new PlatformModuleService(new ModuleMemoryDao());
        PlatformModule managed = module("crm.customer", "crm");
        managed.setSystemManaged(Boolean.TRUE);
        PlatformManagedMutationContext.runAsPlatformManaged(() -> service.insert(managed));

        PlatformModule protectedUpdate = new PlatformModule();
        protectedUpdate.setAlias(managed.getAlias());
        protectedUpdate.setVersion(managed.getVersion());
        protectedUpdate.setApplicationAlias("crm");
        protectedUpdate.setTitle("Changed");

        assertThatThrownBy(() -> service.update(protectedUpdate))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
        assertThatThrownBy(() -> service.delete(managed.getAlias()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
    }

    @Test
    void shouldAllowOrdinaryEnabledAndSortUpdateOnSystemManagedModule() {
        PlatformModuleService service = new PlatformModuleService(new ModuleMemoryDao());
        PlatformModule managed = module("crm.customer", "crm");
        managed.setSystemManaged(Boolean.TRUE);
        PlatformManagedMutationContext.runAsPlatformManaged(() -> service.insert(managed));

        PlatformModule update = new PlatformModule();
        update.setAlias(managed.getAlias());
        update.setVersion(managed.getVersion());
        update.setEnabled(Boolean.FALSE);
        update.setSortOrder(50);

        assertThat(service.update(update)).isEqualTo(1);

        PlatformModule selected = service.select(managed.getAlias());
        assertThat(selected.getEnabled()).isFalse();
        assertThat(selected.getSortOrder()).isEqualTo(50);
        assertThat(selected.getTitle()).isEqualTo("crm.customer");
        assertThat(selected.getSystemManaged()).isTrue();
    }

    @Test
    void shouldProjectManagedModuleMutationBoundaryToRecordActions() {
        PlatformModuleService service = new PlatformModuleService(new ModuleMemoryDao());
        PlatformModule managed = module("crm.customer", "crm");
        managed.setSystemManaged(Boolean.TRUE);
        PlatformManagedMutationContext.runAsPlatformManaged(() -> service.insert(managed));

        assertThat(service.ordinaryRecordActionAvailability("update", managed))
                .hasValueSatisfying(decision -> assertThat(decision.reason()).isEqualTo("平台托管记录不可编辑"));
        assertThat(service.ordinaryRecordActionAvailability("delete", managed))
                .hasValueSatisfying(decision -> assertThat(decision.reason()).isEqualTo("平台托管记录不可删除"));
        assertThat(service.ordinaryRecordActionAvailability("disable", managed)).isEmpty();
        assertThat(service.ordinaryRecordActionAvailability("sort", managed)).isEmpty();
    }

    private PlatformModule module(String alias, String applicationAlias) {
        PlatformModule module = new PlatformModule();
        module.setAlias(alias);
        module.setApplicationAlias(applicationAlias);
        module.setTitle(alias);
        return module;
    }

    private static class ModuleMemoryDao implements BaseDao<PlatformModule, String> {
        private final Map<String, PlatformModule> rows = new LinkedHashMap<>();

        @Override
        public boolean ensureTable() {
            return true;
        }

        @Override
        public String insert(PlatformModule entity) {
            rows.put(entity.getId(), entity);
            return entity.getId();
        }

        @Override
        public int updateById(PlatformModule entity) {
            rows.put(entity.getId(), entity);
            return 1;
        }

        @Override
        public int updateByIdAndCondition(PlatformModule entity, Map<String, Object> conditions) {
            rows.put(entity.getId(), entity);
            return 1;
        }

        @Override
        public int deleteById(String id) {
            return rows.remove(id) == null ? 0 : 1;
        }

        @Override
        public int deleteByIdAndCondition(String id, Map<String, Object> conditions) {
            return deleteById(id);
        }

        @Override
        public boolean existsById(String id) {
            return rows.containsKey(id);
        }

        @Override
        public PlatformModule findById(String id) {
            return rows.get(id);
        }

        @Override
        public List<PlatformModule> query(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
            List<PlatformModule> filtered = rows.values().stream()
                    .filter(row -> matches(row, criteria))
                    .sorted(Comparator.comparing(PlatformModule::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .toList();
            int from = Math.min(pageRequest.getOffset(), filtered.size());
            int to = Math.min(from + pageRequest.getLimit(), filtered.size());
            return new ArrayList<>(filtered.subList(from, to));
        }

        @Override
        public List<PlatformModule> list(Criteria criteria, Sort... sorts) {
            return rows.values().stream()
                    .filter(row -> matches(row, criteria))
                    .sorted(Comparator.comparing(PlatformModule::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .toList();
        }

        @Override
        public PageResult<PlatformModule> pageQuery(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
            List<PlatformModule> records = query(criteria, pageRequest, sorts);
            return PageResult.of(records, records.size(), pageRequest);
        }

        @Override
        public long count(Criteria criteria) {
            return rows.values().stream().filter(row -> matches(row, criteria)).count();
        }

        @Override
        public int upsert(PlatformModule entity) {
            rows.put(entity.getId(), entity);
            return 1;
        }

        private boolean matches(PlatformModule row, Criteria criteria) {
            if (criteria == null || criteria.isEmpty()) {
                return true;
            }
            return matchesGroup(row, criteria.getRoot());
        }

        private boolean matchesGroup(PlatformModule row, CriteriaGroup group) {
            Boolean matched = null;
            for (CriteriaGroup.Entry entry : group.getEntries()) {
                boolean entryMatched = matchesNode(row, entry.getNode());
                if (matched == null) {
                    matched = entryMatched;
                } else if (isOrJoin(entry)) {
                    matched = matched || entryMatched;
                } else {
                    matched = matched && entryMatched;
                }
            }
            return matched == null || matched;
        }

        private boolean matchesNode(PlatformModule row, Object node) {
            if (node instanceof CriteriaClause clause) {
                return matchesClause(row, clause);
            }
            if (node instanceof CriteriaGroup group) {
                return matchesGroup(row, group);
            }
            return true;
        }

        private boolean matchesClause(PlatformModule row, CriteriaClause clause) {
            if (clause.getOperator() != CriteriaOperator.EQ) {
                return true;
            }
            Object expected = clause.getValues().getFirst();
            Object actual = switch (clause.getField()) {
                case "id" -> row.getId();
                case "tenantId" -> row.getTenantId();
                case "applicationAlias" -> row.getApplicationAlias();
                case "parentId" -> row.getParentId();
                case "deleted" -> row.getDeleted();
                case "enabled" -> row.getEnabled();
                default -> null;
            };
            return expected == null ? actual == null : expected.equals(actual);
        }

        private boolean isOrJoin(CriteriaGroup.Entry entry) {
            try {
                Method method = entry.getClass().getMethod("getJoin");
                return "OR".equals(String.valueOf(method.invoke(entry)));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read criteria join", e);
            }
        }
    }
}
