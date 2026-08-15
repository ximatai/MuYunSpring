package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategory;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationServiceContractTest {
    @Test
    void shouldRequireExplicitApplicationAliasBeforeIdGeneration() {
        ApplicationService service = new ApplicationService(new ApplicationMemoryDao());
        Application application = new Application();
        application.setTitle("CRM");

        assertThatThrownBy(() -> service.insert(application))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("applicationAlias");
    }

    @Test
    void shouldUseAliasAsApplicationIdAndFillAbilityDefaults() {
        ApplicationService service = new ApplicationService(new ApplicationMemoryDao());
        Application application = new Application();
        application.setAlias("crm");
        application.setTitle("CRM");

        String id = service.insert(application);

        assertThat(id).isEqualTo("crm");
        assertThat(application.getId()).isEqualTo("crm");
        assertThat(application.getEnabled()).isTrue();
    }

    @Test
    void shouldRejectInvalidApplicationAlias() {
        ApplicationService service = new ApplicationService(new ApplicationMemoryDao());
        Application application = new Application();
        application.setAlias("crm-app");
        application.setTitle("CRM");

        assertThatThrownBy(() -> service.insert(application))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("applicationAlias");
    }

    @Test
    void shouldReorderApplicationsInGlobalScope() {
        ApplicationMemoryDao dao = new ApplicationMemoryDao();
        ApplicationService service = new ApplicationService(dao);
        service.insert(application("crm"));
        service.insert(application("sales"));

        service.reorder(List.of("sales", "crm"));

        assertThat(service.sortedList(Criteria.of()))
                .extracting(Application::getAlias)
                .containsExactly("sales", "crm");
    }

    @Test
    void shouldProtectPlatformManagedApplicationsAndExplainUnavailableActions() {
        ApplicationService service = new ApplicationService(new ApplicationMemoryDao());
        Application managed = application("platform");
        managed.setSystemManaged(Boolean.TRUE);
        PlatformManagedMutationContext.runAsPlatformManaged(() -> service.insert(managed));

        Application update = new Application();
        update.setAlias(managed.getAlias());
        update.setVersion(managed.getVersion());
        update.setTitle("Changed");

        assertThatThrownBy(() -> service.update(update))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
        assertThatThrownBy(() -> service.delete(managed.getAlias()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("platform-managed");
        assertThat(service.availability(ApplicationService.MODULE_ALIAS, "update", managed.getAlias()))
                .hasValueSatisfying(decision -> {
                    assertThat(decision.available()).isFalse();
                    assertThat(decision.reason()).isEqualTo("平台托管应用不可编辑");
                });
        assertThat(service.availability(ApplicationService.MODULE_ALIAS, "delete", managed.getAlias()))
                .hasValueSatisfying(decision -> assertThat(decision.reason()).isEqualTo("平台托管应用不可删除"));
        assertThat(service.availability(ApplicationService.MODULE_ALIAS, "disable", managed.getAlias()))
                .hasValueSatisfying(decision -> assertThat(decision.reason()).isEqualTo("平台托管应用不可变更启用状态"));
    }

    @Test
    void shouldOnlyExposeEnabledNonSystemApplicationsToTenantEntitlements() {
        ApplicationService service = new ApplicationService(new ApplicationMemoryDao());
        service.insert(application("crm"));
        Application platform = application(ApplicationService.PLATFORM_APPLICATION_ALIAS);
        service.insert(platform);

        service.requireEnabledForTenant("crm");
        assertThat(service.isEnabledForTenant("crm")).isTrue();
        assertThatThrownBy(() -> service.requireEnabledForTenant("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not active");
        assertThatThrownBy(() -> service.requireEnabledForTenant(ApplicationService.PLATFORM_APPLICATION_ALIAS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be opened");

        Application crm = service.select("crm");
        crm.setEnabled(false);
        service.update(crm);
        assertThat(service.isEnabledForTenant("crm")).isFalse();
        assertThatThrownBy(() -> service.requireEnabledForTenant("crm"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void shouldReadApplicationCatalogFromGlobalScopeInsideTenantContext() {
        ApplicationService service = new ApplicationService(new ApplicationMemoryDao());
        service.insert(application("crm"));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThat(service.isEnabledForTenant("crm")).isTrue();
        }
    }

    @Test
    void shouldRejectDeletingApplicationReferencedByModules() {
        PlatformModuleService moduleService = new PlatformModuleService(new TestMemoryDao<>());
        ApplicationService service = applicationService(moduleService, new MetadataService(new TestMemoryDao<>()),
                new DictionaryCategoryService(new TestMemoryDao<>()));
        service.insert(application("crm"));
        moduleService.insert(module("crm.customer", "crm"));

        assertThatThrownBy(() -> service.delete("crm"))
                .isInstanceOf(PlatformException.class)
                .satisfies(cause -> assertApplicationReferencedError(cause, "module"))
                .hasMessageContaining("模块");
    }

    @Test
    void shouldRejectDeletingApplicationReferencedByMetadata() {
        MetadataService metadataService = new MetadataService(new TestMemoryDao<>());
        ApplicationService service = applicationService(new PlatformModuleService(new TestMemoryDao<>()), metadataService,
                new DictionaryCategoryService(new TestMemoryDao<>()));
        service.insert(application("crm"));
        metadataService.insert(metadata("customer", "crm"));

        assertThatThrownBy(() -> service.delete("crm"))
                .isInstanceOf(PlatformException.class)
                .satisfies(cause -> assertApplicationReferencedError(cause, "metadata"))
                .hasMessageContaining("元数据");
    }

    @Test
    void shouldRejectDeletingApplicationReferencedByDictionaryCategories() {
        DictionaryCategoryService categoryService = new DictionaryCategoryService(new TestMemoryDao<>());
        ApplicationService service = applicationService(new PlatformModuleService(new TestMemoryDao<>()),
                new MetadataService(new TestMemoryDao<>()), categoryService);
        service.insert(application("crm"));
        categoryService.insert(dictionaryCategory("status", "crm"));

        assertThatThrownBy(() -> service.delete("crm"))
                .isInstanceOf(PlatformException.class)
                .satisfies(cause -> assertApplicationReferencedError(cause, "dictionaryCategory"))
                .hasMessageContaining("字典类目");
    }

    @Test
    void shouldRejectDeletingApplicationReferencedByContributedApplicationFact() {
        ApplicationReferenceContributor contributor = new ApplicationReferenceContributor() {
            @Override
            public String resourceKey() {
                return "workflowDefinition";
            }

            @Override
            public String resourceName() {
                return "工作流定义";
            }

            @Override
            public boolean hasReferenceTo(String applicationAlias) {
                return "crm".equals(applicationAlias);
            }
        };
        ApplicationService service = new ApplicationService(new ApplicationMemoryDao(), List.of(contributor));
        service.insert(application("crm"));

        assertThatThrownBy(() -> service.delete("crm"))
                .isInstanceOf(PlatformException.class)
                .satisfies(cause -> assertApplicationReferencedError(cause, "workflowDefinition"))
                .hasMessageContaining("工作流定义");
    }

    private Application application(String alias) {
        Application application = new Application();
        application.setAlias(alias);
        application.setTitle(alias);
        return application;
    }

    private ApplicationService applicationService(PlatformModuleService moduleService,
                                                  MetadataService metadataService,
                                                  DictionaryCategoryService dictionaryCategoryService) {
        return new ApplicationService(new ApplicationMemoryDao(), List.of(moduleService, metadataService,
                dictionaryCategoryService));
    }

    private PlatformModule module(String alias, String applicationAlias) {
        PlatformModule module = new PlatformModule();
        module.setAlias(alias);
        module.setTitle(alias);
        module.setApplicationAlias(applicationAlias);
        return module;
    }

    private Metadata metadata(String alias, String applicationAlias) {
        Metadata metadata = new Metadata();
        metadata.setAlias(alias);
        metadata.setTitle(alias);
        metadata.setApplicationAlias(applicationAlias);
        return metadata;
    }

    private DictionaryCategory dictionaryCategory(String alias, String applicationAlias) {
        DictionaryCategory category = new DictionaryCategory();
        category.setAlias(alias);
        category.setTitle(alias);
        category.setApplicationAlias(applicationAlias);
        return category;
    }

    private void assertApplicationReferencedError(Throwable cause, String referencedResource) {
        PlatformException exception = (PlatformException) cause;
        assertThat(exception.code()).isEqualTo(PlatformErrorCodes.RESOURCE_IN_USE);
        assertThat(exception.httpStatus()).isEqualTo(409);
        assertThat(exception.scope().moduleAlias()).isEqualTo(ApplicationService.MODULE_ALIAS);
        assertThat(exception.scope().actionCode()).isEqualTo("delete");
        assertThat(exception.targets()).extracting("recordId").containsExactly("crm");
        assertThat(exception.details())
                .containsEntry("applicationAlias", "crm")
                .containsEntry("referencedResource", referencedResource);
    }

    private static class ApplicationMemoryDao implements BaseDao<Application, String> {
        private final Map<String, Application> rows = new LinkedHashMap<>();

        @Override
        public boolean ensureTable() {
            return true;
        }

        @Override
        public String insert(Application entity) {
            rows.put(entity.getId(), entity);
            return entity.getId();
        }

        @Override
        public int updateById(Application entity) {
            rows.put(entity.getId(), entity);
            return 1;
        }

        @Override
        public int updateByIdAndCondition(Application entity, Map<String, Object> conditions) {
            rows.put(entity.getId(), entity);
            return 1;
        }

        @Override
        public int deleteById(String id) {
            return rows.remove(id) == null ? 0 : 1;
        }

        @Override
        public int deleteByIdAndCondition(String id, Map<String, Object> conditions) {
            return 1;
        }

        @Override
        public boolean existsById(String id) {
            return rows.containsKey(id);
        }

        @Override
        public Application findById(String id) {
            return rows.get(id);
        }

        @Override
        public List<Application> query(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
            List<Application> filtered = rows.values().stream()
                    .filter(row -> matches(row, criteria))
                    .sorted(Comparator.comparing(Application::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .toList();
            int from = Math.min(pageRequest.getOffset(), filtered.size());
            int to = Math.min(from + pageRequest.getLimit(), filtered.size());
            return new ArrayList<>(filtered.subList(from, to));
        }

        @Override
        public List<Application> list(Criteria criteria, Sort... sorts) {
            return rows.values().stream()
                    .filter(row -> matches(row, criteria))
                    .sorted(Comparator.comparing(Application::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .toList();
        }

        @Override
        public PageResult<Application> pageQuery(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
            return PageResult.of(List.of(), 0, pageRequest);
        }

        @Override
        public long count(Criteria criteria) {
            return rows.values().stream().filter(row -> matches(row, criteria)).count();
        }

        @Override
        public int upsert(Application entity) {
            rows.put(entity.getId(), entity);
            return 1;
        }

        private boolean matches(Application row, Criteria criteria) {
            if (criteria == null || criteria.isEmpty()) {
                return true;
            }
            return matchesGroup(row, criteria.getRoot());
        }

        private boolean matchesGroup(Application row, CriteriaGroup group) {
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

        private boolean matchesNode(Application row, Object node) {
            if (node instanceof CriteriaClause clause) {
                return matchesClause(row, clause);
            }
            if (node instanceof CriteriaGroup group) {
                return matchesGroup(row, group);
            }
            return true;
        }

        private boolean matchesClause(Application row, CriteriaClause clause) {
            if (clause.getOperator() != CriteriaOperator.EQ) {
                return true;
            }
            Object expected = clause.getValues().getFirst();
            Object actual = switch (clause.getField()) {
                case "id" -> row.getId();
                case "deleted" -> row.getDeleted();
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
