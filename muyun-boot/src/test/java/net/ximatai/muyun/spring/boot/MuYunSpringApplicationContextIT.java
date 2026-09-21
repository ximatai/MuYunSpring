package net.ximatai.muyun.spring.boot;

import com.fasterxml.jackson.databind.JsonNode;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
import net.ximatai.muyun.spring.platform.web.PlatformStaticActionContribution;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebProjection;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinitionCatalog;
import net.ximatai.muyun.spring.platform.web.StaticRecordReadProjectionService;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.platform.module.StaticServiceAbilityCompiler;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.RequestTenantVerifier;
import net.ximatai.muyun.spring.web.ScopedWeb;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebQueryCondition;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.web.WebSort;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint;
import net.ximatai.muyun.spring.ability.deletion.DeletionRecoveryAbility;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.application.Application;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.deletion.DeletionEntry;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinItem;
import net.ximatai.muyun.spring.platform.deletion.RestoreEntryResult;
import net.ximatai.muyun.spring.platform.deletion.RestoreReport;
import net.ximatai.muyun.spring.platform.deletion.StaticDeletionRecoveryResourceResolver;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.platform.module.ModuleActionContribution;
import net.ximatai.muyun.spring.platform.module.ModuleActionContributionRegistrar;
import net.ximatai.muyun.spring.platform.module.ModuleActionSourceType;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldChangeSetDraft;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldForm;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldOwnership;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreview;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMainMetadataCreateCommand;
import net.ximatai.muyun.spring.platform.metadata.ModuleMainMetadataCreationResult;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageContractType;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionPublishService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationScopeType;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariant;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariantService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategory;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.user.LoginResult;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.iam.user.UserSession;
import net.ximatai.muyun.spring.iam.user.UserSessionDao;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.iam.role.Role;
import net.ximatai.muyun.spring.iam.role.RoleAssignmentType;
import net.ximatai.muyun.spring.iam.role.DataScopePolicy;
import net.ximatai.muyun.spring.iam.role.RoleKind;
import net.ximatai.muyun.spring.iam.role.RoleOwnerScopeType;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.role.RoleSharePolicy;
import net.ximatai.muyun.spring.iam.role.TenantScopePolicy;
import net.ximatai.muyun.spring.iam.logging.LoginAuditGovernanceService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(
        classes = MuYunSpringApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class MuYunSpringApplicationContextIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private static final Path RUNTIME_LOG_DIRECTORY = createRuntimeLogDirectory();

    @Autowired
    private UserSessionService userSessionService;

    @Autowired
    private UserSessionDao userSessionDao;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StaticRecordReadProjectionService staticRecordReadProjectionService;

    @Autowired
    private StaticAbilityCatalog staticAbilityCatalog;

    @Autowired
    private StaticModuleDefinitionCatalog staticModuleDefinitionCatalog;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private PlatformModuleService moduleService;

    @Autowired
    private ModuleMetadataOrchestrationService metadataOrchestrationService;

    @Autowired
    private MetadataRelationChangeSetPreviewService metadataChangeSetPreviewService;

    @Autowired
    private MetadataRelationChangeSetApplyService metadataChangeSetApplyService;

    @Autowired
    private ModuleMetadataRelationService moduleMetadataRelationService;

    @Autowired
    private ModuleActionContributionRegistrar moduleActionRegistrar;

    @Autowired
    private PlatformDynamicRuntimeRefreshService dynamicRuntimeRefreshService;

    @Autowired
    private DynamicRecordService dynamicRecordService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PlatformPageDefinitionService pageDefinitionService;

    @Autowired
    private PlatformPresentationVariantService presentationVariantService;

    @Autowired
    private PlatformPresentationRevisionService presentationRevisionService;

    @Autowired
    private PlatformPresentationRevisionPublishService presentationRevisionPublishService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantApplicationService tenantApplicationService;

    @Autowired
    private DictionaryCategoryService dictionaryCategoryService;

    @Autowired
    private StaticDeletionRecoveryResourceResolver staticDeletionRecoveryResourceResolver;

    @Autowired
    private RecycleBinFacade recycleBinFacade;

    @Autowired
    private RegisteredWebEndpointCatalog registeredWebEndpointCatalog;

    @Autowired
    private ApplicationContext applicationContext;

    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUpRestTemplate() {
        restTemplate = new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://localhost:" + port + "/api"));
    }

    private static Path createRuntimeLogDirectory() {
        try {
            return Files.createTempDirectory("muyun-runtime-log-it-");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("failed to create runtime log integration-test directory", ex);
        }
    }

    @AfterAll
    static void deleteRuntimeLogDirectory() throws java.io.IOException {
        try (Stream<Path> files = Files.walk(RUNTIME_LOG_DIRECTORY)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
        registry.add("muyun.platform.runtime-log.directory", () -> RUNTIME_LOG_DIRECTORY.toString());
        registry.add("logging.file.name", () -> RUNTIME_LOG_DIRECTORY.resolve("application.log").toString());
    }

    @Test
    void shouldLoadApplicationContextWithRealDatabase() {
        assertThat(applicationContext.containsBean("educationApplication")).isFalse();
        assertThat(applicationContext.containsBean("studentService")).isFalse();
        assertThat(applicationContext.containsBean("studentDao")).isFalse();
        assertThat(staticModuleDefinitionCatalog.find("education.student")).isEmpty();
        assertThat(staticModuleDefinitionCatalog.find(EmployeeAccountService.MODULE_ALIAS))
                .get()
                .satisfies(definition -> {
                    assertThat(definition.entryType()).isEqualTo(ModuleEntryType.MODULE);
                    assertThat(definition.entities()).isNotEmpty();
                    assertThat(definition.actions()).isNotEmpty();
                });
        assertThat(registeredWebEndpointCatalog.endpoints().stream()
                .filter(endpoint -> endpoint.definition().moduleAlias().equals(EmployeeAccountService.MODULE_ALIAS)))
                .isEmpty();
        assertThat(registeredWebEndpointCatalog.endpoints().stream()
                .filter(endpoint -> endpoint.definition().moduleAlias().equals("platform.application")))
                .extracting(endpoint -> endpoint.definition().endpointId())
                .contains("platform.application.enable.enable", "platform.application.enable.disable",
                        "platform.application.sort.sort", "platform.application.recycleBin.query",
                        "platform.application.recycleBin.restore");
        assertThat(registeredWebEndpointCatalog.endpoints().stream()
                .filter(endpoint -> endpoint.definition().moduleAlias().equals("platform.application")))
                .filteredOn(endpoint -> !endpoint.definition().abilityCode().equals("controller")
                        && !endpoint.definition().abilityCode().equals("openApi"))
                .allSatisfy(endpoint -> assertThat(endpoint.definition().source())
                        .isEqualTo(ResolvedWebEndpoint.Source.STATIC_ABILITY));
        assertThat(registeredWebEndpointCatalog.endpoints().stream()
                .filter(endpoint -> endpoint.definition().moduleAlias().equals("platform.application")))
                .filteredOn(endpoint -> endpoint.definition().abilityCode().equals("openApi"))
                .allSatisfy(endpoint -> assertThat(endpoint.definition().source())
                        .isEqualTo(ResolvedWebEndpoint.Source.STATIC_EXPLICIT));
        assertThat(registeredWebEndpointCatalog.endpoints().stream()
                .filter(endpoint -> endpoint.definition().moduleAlias().equals("platform.application")))
                .filteredOn(endpoint -> endpoint.definition().abilityCode().equals("controller"))
                .isNotEmpty()
                .allSatisfy(endpoint -> assertThat(endpoint.definition().source())
                        .isEqualTo(ResolvedWebEndpoint.Source.STATIC_EXPLICIT));
        assertThat(registeredWebEndpointCatalog.endpoints().stream()
                .filter(endpoint -> endpoint.definition().moduleAlias().equals("iam.organization"))
                .filter(endpoint -> endpoint.definition().source() == ResolvedWebEndpoint.Source.STATIC_ABILITY))
                .extracting(endpoint -> endpoint.definition().endpointId())
                .contains("iam.organization.enable.enable", "iam.organization.enable.disable",
                        "iam.organization.tree.tree", "iam.organization.tree.subtree",
                        "iam.organization.tree.sort");
        assertThat(registeredWebEndpointCatalog.endpoints().stream()
                .filter(endpoint -> endpoint.definition().moduleAlias().equals("platform.module"))
                .filter(endpoint -> endpoint.definition().source() == ResolvedWebEndpoint.Source.STATIC_ABILITY))
                .extracting(endpoint -> endpoint.definition().endpointId())
                .contains("platform.module.tree.tree", "platform.module.tree.treeQuery",
                        "platform.module.tree.subtree", "platform.module.tree.sort");
        assertThat(registeredWebEndpointCatalog.endpoints().stream()
                .filter(endpoint -> endpoint.definition().abilityCode().startsWith("item.")))
                .extracting(endpoint -> endpoint.definition().executionPolicy().actionCode())
                .contains("item_enable", "item_disable", "item_tree", "item_sort");
        assertThat(registeredWebEndpointCatalog.endpoints().stream()
                .map(endpoint -> endpoint.definition().path()))
                .contains("/platform.measure_unit/categories/enable/{id}",
                        "/platform.measure_unit/categories/{categoryAlias}/units/enable/{id}",
                        "/platform.measure_unit/conversion-rules/enable/{id}");
    }

    @Test
    void shouldWireSingleRequestTenantVerifierIntoDefaultCurrentUserWebFilter() {
        Map<String, RequestTenantVerifier> verifiers = applicationContext.getBeansOfType(RequestTenantVerifier.class);
        Map<String, CurrentUserWebFilter> filters = applicationContext.getBeansOfType(CurrentUserWebFilter.class);

        assertThat(verifiers).hasSize(1);
        assertThat(filters).hasSize(1);
        assertThat(ReflectionTestUtils.getField(filters.values().iterator().next(), "requestTenantVerifier"))
                .isSameAs(verifiers.values().iterator().next());
    }

    @Test
    void shouldSortDictionaryCategoryThroughStandardHttpWithNavigatorScope() {
        DictionaryCategory platform = category("it-sort-platform", "platform", "it_sort_platform", null);
        DictionaryCategory platformNeighbor = category("it-sort-platform-neighbor", "platform", "it_sort_platform_neighbor", null);
        DictionaryCategory iam = category("it-sort-iam", "iam", "it_sort_iam", null);
        PlatformManagedMutationContext.runAsPlatformManaged(() -> {
            dictionaryCategoryService.insert(platform);
            dictionaryCategoryService.insert(platformNeighbor);
            dictionaryCategoryService.insert(iam);
        });

        String token = issueSuperAdminSessionToken();
        HttpHeaders headers = bearerHeaders(token);
        String body = """
                {"previousId":"%s","parentId":"root","scope":{"externalQueryValues":{"applicationAlias":"platform"},"navigatorHostModuleAlias":"platform.dictionary_category","navigatorTargetLevelKey":"category"}}
        """.formatted(platformNeighbor.getId());
        ResponseEntity<JsonNode> sorted = restTemplate.exchange(
                "/platform.dictionary_category/sort/%s".formatted(platform.getId()), HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(sorted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sorted.getBody()).isNotNull();
        assertThat(sorted.getBody().path("data").asInt()).isEqualTo(1);
        assertThat(sorted.getBody().path("changes").toString()).contains("platform.dictionary_category");

        List<String> beforeRejectedMove = dictionaryCategoryService.rootCategories("platform").stream()
                .map(DictionaryCategory::getId).toList();
        assertThat(beforeRejectedMove.indexOf(platform.getId()))
                .isEqualTo(beforeRejectedMove.indexOf(platformNeighbor.getId()) + 1);
        String foreignBody = """
                {"previousId":"%s","parentId":"root","scope":{"externalQueryValues":{"applicationAlias":"platform"},"navigatorHostModuleAlias":"platform.dictionary_category","navigatorTargetLevelKey":"category"}}
                """.formatted(iam.getId());
        ResponseEntity<JsonNode> rejected = restTemplate.exchange(
                "/platform.dictionary_category/sort/%s".formatted(platform.getId()), HttpMethod.POST,
                new HttpEntity<>(foreignBody, headers), JsonNode.class);
        assertThat(rejected.getStatusCode().is4xxClientError()).isTrue();
        assertThat(dictionaryCategoryService.rootCategories("platform").stream()
                .map(DictionaryCategory::getId).toList()).isEqualTo(beforeRejectedMove);

        String foreignParentBody = """
                {"parentId":"%s","scope":{"externalQueryValues":{"applicationAlias":"platform"},"navigatorHostModuleAlias":"platform.dictionary_category","navigatorTargetLevelKey":"category"}}
                """.formatted(iam.getId());
        ResponseEntity<JsonNode> parentRejected = restTemplate.exchange(
                "/platform.dictionary_category/sort/%s".formatted(platform.getId()), HttpMethod.POST,
                new HttpEntity<>(foreignParentBody, headers), JsonNode.class);
        assertThat(parentRejected.getStatusCode().is4xxClientError()).isTrue();
        assertThat(dictionaryCategoryService.rootCategories("platform").stream()
                .map(DictionaryCategory::getId).toList()).isEqualTo(beforeRejectedMove);
    }

    private DictionaryCategory category(String id, String applicationAlias, String alias, String parentId) {
        DictionaryCategory category = new DictionaryCategory();
        category.setId(id);
        category.setApplicationAlias(applicationAlias);
        category.setAlias(alias);
        category.setCategoryKind(net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryKind.FOLDER);
        category.setTitle(alias);
        category.setParentId(parentId);
        category.setEnabled(true);
        return category;
    }

    @Test
    void shouldRegisterTenantAsStaticReferenceTarget() {
        assertThat(applicationContext.getBean(TenantService.class)).isInstanceOf(ReferenceAbility.class);
        assertThat(staticAbilityCatalog.abilities())
                .extracting(ability -> ReferenceTargets.of(ability))
                .contains(ReferenceTarget.of("iam", "tenant"));
        assertThat(PlatformAbilityRuntime.referenceTargetResolver()
                .resolve(ReferenceTarget.of("iam", "tenant")))
                .isPresent()
                .get()
                .isInstanceOf(TenantService.class);
    }

    @Test
    void shouldRegisterApplicationAsStaticReferenceTargetBeforeStaticModuleRegistration() {
        assertThat(applicationContext.getBean(ApplicationService.class)).isInstanceOf(ReferenceAbility.class);
        assertThat(staticAbilityCatalog.abilities())
                .extracting(ability -> ReferenceTargets.of(ability))
                .contains(ReferenceTarget.of("platform", "application"));
        assertThat(PlatformAbilityRuntime.referenceTargetResolver()
                .resolve(ReferenceTarget.of("platform", "application")))
                .isPresent()
                .get()
                .isInstanceOf(ApplicationService.class);
    }

    @Test
    void shouldKeepAllStaticAbilityEndpointMappingsComplete() {
        Set<ExpectedEndpoint> expected = expectedStaticAbilityEndpoints();
        Set<ExpectedEndpoint> actual = registeredWebEndpointCatalog.endpoints().stream()
                .filter(endpoint -> endpoint.definition().source() == ResolvedWebEndpoint.Source.STATIC_ABILITY)
                .map(endpoint -> new ExpectedEndpoint(
                        endpoint.definition().moduleAlias(),
                        endpoint.definition().action(),
                        endpoint.definition().method(),
                        endpoint.definition().path()
                ))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private Set<ExpectedEndpoint> expectedStaticAbilityEndpoints() {
        Set<ExpectedEndpoint> expected = new LinkedHashSet<>();
        addModuleEndpoints(expected);
        addContributionEndpoints(expected);
        addAdditionalProjectionEndpoints(expected);
        return Set.copyOf(expected);
    }

    private void addModuleEndpoints(Set<ExpectedEndpoint> expected) {
        for (String beanName : applicationContext.getBeanNamesForAnnotation(PlatformStaticModule.class)) {
            Object bean = applicationContext.getBean(beanName);
            if (!(bean instanceof ScopedWeb<?> anchor)) {
                continue;
            }
            Class<?> type = AopUtils.getTargetClass(bean);
            PlatformStaticModule module = AnnotationUtils.findAnnotation(type, PlatformStaticModule.class);
            if (module != null) {
                addExpectedEndpoints(expected, module.alias(), basePaths(type, module.alias()), anchor.service(), Set.of());
            }
        }
    }

    private void addContributionEndpoints(Set<ExpectedEndpoint> expected) {
        for (String beanName : applicationContext.getBeanNamesForAnnotation(PlatformStaticActionContribution.class)) {
            Object bean = applicationContext.getBean(beanName);
            if (!(bean instanceof ScopedWeb<?> anchor)) {
                continue;
            }
            Class<?> type = AopUtils.getTargetClass(bean);
            PlatformStaticActionContribution contribution =
                    AnnotationUtils.findAnnotation(type, PlatformStaticActionContribution.class);
            if (contribution != null) {
                addExpectedEndpoints(expected, contribution.targetModule(),
                        basePaths(type, contribution.targetModule()), anchor.service(), Set.of());
            }
        }
    }

    private void addAdditionalProjectionEndpoints(Set<ExpectedEndpoint> expected) {
        for (String beanName : applicationContext.getBeanNamesForAnnotation(PlatformStaticWebProjection.class)) {
            Object bean = applicationContext.getBean(beanName);
            if (!(bean instanceof ScopedWeb<?> anchor)) {
                continue;
            }
            Class<?> type = AopUtils.getTargetClass(bean);
            PlatformStaticWebProjection projection =
                    AnnotationUtils.findAnnotation(type, PlatformStaticWebProjection.class);
            if (projection != null) {
                addExpectedEndpoints(expected, projection.module(), basePaths(type, projection.module()),
                        anchor.service(), disabledOperations(projection));
            }
        }
    }

    private void addExpectedEndpoints(Set<ExpectedEndpoint> expected,
                                      String moduleAlias,
                                      List<String> basePaths,
                                      Object service,
                                      Set<PlatformAction> locallyDisabled) {
        if (service instanceof EnableAbility<?> && !locallyDisabled.contains(PlatformAction.ENABLE)
                && StaticServiceAbilityCompiler.operationMethods(service).containsKey(PlatformAction.ENABLE)) {
            add(expected, moduleAlias, PlatformAction.ENABLE, RequestMethod.POST, basePaths, "/enable/{id}");
        }
        if (service instanceof EnableAbility<?> && !locallyDisabled.contains(PlatformAction.DISABLE)
                && StaticServiceAbilityCompiler.operationMethods(service).containsKey(PlatformAction.DISABLE)) {
            add(expected, moduleAlias, PlatformAction.DISABLE, RequestMethod.POST, basePaths, "/disable/{id}");
        }
        if (service instanceof TreeAbility<?>) {
            if (enabled(service, PlatformAction.TREE, locallyDisabled)) {
                add(expected, moduleAlias, PlatformAction.TREE, RequestMethod.GET, basePaths, "/tree");
                add(expected, moduleAlias, PlatformAction.TREE, RequestMethod.GET, basePaths, "/tree/{id}");
                add(expected, moduleAlias, PlatformAction.TREE, RequestMethod.POST, basePaths, "/tree/query");
            }
            if (enabled(service, PlatformAction.SORT, locallyDisabled)) {
                add(expected, moduleAlias, PlatformAction.SORT, RequestMethod.POST, basePaths, "/sort/{id}");
            }
        } else if (service instanceof SortAbility<?> && enabled(service, PlatformAction.SORT, locallyDisabled)) {
            add(expected, moduleAlias, PlatformAction.SORT, RequestMethod.POST, basePaths, "/sort/{id}");
        }
        if (new net.ximatai.muyun.spring.dynamic.capability.DataScopeCapabilityModule().staticFacet().orElseThrow().supports(service)
                && enabled(service, PlatformAction.MANAGE_PERMISSIONS, locallyDisabled)) {
            add(expected, moduleAlias, PlatformAction.MANAGE_PERMISSIONS, RequestMethod.GET, basePaths, "/permissions/{id}");
            add(expected, moduleAlias, PlatformAction.MANAGE_PERMISSIONS, RequestMethod.POST, basePaths, "/permissions/{id}");
            add(expected, moduleAlias, PlatformAction.MANAGE_PERMISSIONS, RequestMethod.GET, basePaths, "/permissions/{id}/candidates");
        }
        if (service instanceof RecycleBinAbility<?> recycleBinAbility) {
            if (enabled(service, PlatformAction.RECYCLE_BIN_QUERY, locallyDisabled)) {
                add(expected, moduleAlias, PlatformAction.RECYCLE_BIN_QUERY, RequestMethod.POST,
                        basePaths, "/recycle-bin/query");
                add(expected, moduleAlias, PlatformAction.RECYCLE_BIN_QUERY, RequestMethod.GET,
                        basePaths, "/recycle-bin/view/{id}");
            }
            if (enabled(service, PlatformAction.RECYCLE_BIN_RESTORE, locallyDisabled)) {
                add(expected, moduleAlias, PlatformAction.RECYCLE_BIN_RESTORE, RequestMethod.POST,
                        basePaths, "/recycle-bin/{sourceDeleteOperationId}/restore");
            }
            if (recycleBinAbility.isRecycleBinPurgeEnabled()
                    && enabled(service, PlatformAction.RECYCLE_BIN_PURGE, locallyDisabled)) {
                add(expected, moduleAlias, PlatformAction.RECYCLE_BIN_PURGE, RequestMethod.POST,
                        basePaths, "/recycle-bin/{sourceDeleteOperationId}/purge");
            }
        }
    }

    private boolean enabled(Object service, PlatformAction action, Set<PlatformAction> locallyDisabled) {
        return !locallyDisabled.contains(action) && !StaticServiceAbilityCompiler.disabledActions(service).contains(action);
    }

    private Set<PlatformAction> disabledOperations(PlatformStaticWebProjection projection) {
        if (projection.disabledOperations().length == 0) {
            return Set.of();
        }
        EnumSet<PlatformAction> disabled = EnumSet.noneOf(PlatformAction.class);
        java.util.Collections.addAll(disabled, projection.disabledOperations());
        return Set.copyOf(disabled);
    }

    private List<String> basePaths(Class<?> type, String moduleAlias) {
        RequestMapping mapping = AnnotationUtils.findAnnotation(type, RequestMapping.class);
        if (mapping == null) {
            return List.of("/" + moduleAlias);
        }
        String[] values = mapping.path().length == 0 ? mapping.value() : mapping.path();
        return values.length == 0 ? List.of("/" + moduleAlias)
                : Arrays.stream(values).map(this::normalizePath).toList();
    }

    private void add(Set<ExpectedEndpoint> expected,
                     String moduleAlias,
                     PlatformAction action,
                     RequestMethod method,
                     List<String> basePaths,
                     String operationPath) {
        for (String basePath : basePaths) {
            expected.add(new ExpectedEndpoint(moduleAlias, action, method,
                    normalizePath(basePath) + normalizePath(operationPath)));
        }
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) {
            return "";
        }
        String normalized = path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private record ExpectedEndpoint(String moduleAlias,
                                    PlatformAction action,
                                    RequestMethod method,
                                    String path) {
    }

    @Test
    void shouldDeleteTenantWithItsRequiredIamApplicationThroughParentCascade() {
        String tenantId = "tenant_delete_cascade_it";
        try (TenantContext.Scope ignored = TenantContext.system("integration test tenant deletion")) {
            Tenant tenant = new Tenant();
            tenant.setAlias(tenantId);
            tenant.setTitle("Tenant delete cascade integration test");
            tenant.setEnabled(Boolean.TRUE);
            tenantService.insert(tenant);

            assertThat(tenantApplicationService.isApplicationOpened(tenantId, "iam")).isTrue();
            assertThat(tenantService.delete(tenantId, tenant.getVersion())).isEqualTo(1);
            assertThat(tenantApplicationService.isApplicationOpened(tenantId, "iam")).isFalse();
        }
    }

    @Test
    void shouldRegisterTenantApplicationAsStaticRecoveryResource() {
        DeletionEntry entry = new DeletionEntry();
        entry.setResourceModuleAlias(TenantApplicationService.MODULE_ALIAS);
        entry.setResourceEntityAlias("tenant_application");

        assertThat(tenantApplicationService).isInstanceOf(DeletionRecoveryAbility.class);
        assertThat(staticDeletionRecoveryResourceResolver.supports(entry)).isTrue();
        assertThat(staticDeletionRecoveryResourceResolver.resolve(entry)).contains(tenantApplicationService);
    }

    @Test
    void shouldRestoreTenantWithItsRequiredIamApplicationThroughRecycleBin() {
        String tenantId = "tenant_restore_cascade_it";
        try (TenantContext.Scope ignored = TenantContext.system("integration test tenant restoration")) {
            Tenant tenant = new Tenant();
            tenant.setAlias(tenantId);
            tenant.setTitle("Tenant restore cascade integration test");
            tenant.setEnabled(Boolean.TRUE);
            tenantService.insert(tenant);

            assertThat(tenantApplicationService.isApplicationOpened(tenantId, "iam")).isTrue();
            assertThat(tenantService.delete(tenantId, tenant.getVersion())).isEqualTo(1);
            assertThat(tenantService.select(tenantId)).isNull();
            assertThat(tenantApplicationService.isApplicationOpened(tenantId, "iam")).isFalse();

            RecycleBinItem<Tenant> recycleBinItem = recycleBinFacade.list(tenantService, ALL).stream()
                    .filter(item -> tenantId.equals(item.record().getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(recycleBinItem.restorable()).isTrue();

            RestoreReport report = recycleBinFacade.restore(
                    tenantService, recycleBinItem.sourceDeleteOperationId());

            assertThat(report.entries()).hasSize(2);
            assertThat(report.entries()).allMatch(entry -> entry.status() == RestoreEntryResult.Status.RESTORED);
            assertThat(report.entries()).extracting(RestoreEntryResult::moduleAlias)
                    .containsExactlyInAnyOrder(TenantService.MODULE_ALIAS, TenantApplicationService.MODULE_ALIAS);
            assertThat(tenantService.select(tenantId)).isNotNull();
            assertThat(tenantApplicationService.isApplicationOpened(tenantId, "iam")).isTrue();
        }
    }

    @Test
    void shouldPersistAndRevokeLoginSessionWithRealDatabase() {
        assertThat(columnExists("iam_user_session", "max_expires_at")).isTrue();

        LoginResult login = userSessionService.login(
                null,
                UserAccountService.PLATFORM_SUPER_ADMIN_USERNAME,
                "admin123");

        List<UserSession> sessions = userSessionDao.query(Criteria.of()
                        .eq("userId", UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID),
                ALL);
        UserSession session = onlyActiveSession(sessions);
        assertThat(session.getTokenHash()).hasSize(64);
        assertThat(session.getTokenHash()).isNotEqualTo(login.token());
        assertThat(session.getIssuedAt()).isEqualTo(login.issuedAt());
        assertThat(session.getExpiresAt()).isAfter(login.issuedAt());
        assertThat(session.getMaxExpiresAt()).isAfter(session.getExpiresAt());
        assertThat(session.getLastSeenAt()).isEqualTo(login.issuedAt());
        assertThat(session.getRevokedAt()).isNull();

        assertThat(userSessionService.currentUser(login.token()))
                .contains(login.currentUser());

        userSessionService.logout(login.token());

        UserSession revoked = userSessionDao.query(Criteria.of().eq("id", session.getId()), new PageRequest(0, 1))
                .getFirst();
        assertThat(revoked.getRevokedAt()).isNotNull();
        assertThat(revoked.getRevokedReason()).isEqualTo("logout");
        assertThat(revoked.getRevokedAt()).isAfterOrEqualTo(Instant.now().minusSeconds(30));
        assertThat(userSessionService.currentUser(login.token())).isEmpty();
    }

    @Test
    void shouldLoadCurrentUserAndRestrictMenusUntilPasswordChangeThroughRealHttpLogin() {
        ResponseEntity<JsonNode> login = restTemplate.postForEntity("/iam.auth/login",
                Map.of(
                        "username", UserAccountService.PLATFORM_SUPER_ADMIN_USERNAME,
                        "password", "admin123"
                ),
                JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode loginBody = login.getBody();
        assertThat(loginBody).isNotNull();
        String token = loginBody.path("token").asText();
        assertThat(token).isNotBlank();
        assertThat(loginBody.path("currentUser").path("userId").asText())
                .isEqualTo(UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID);
        assertThat(loginBody.path("currentUser").path("system").asBoolean()).isTrue();
        assertThat(loginBody.path("currentUser").has("tenantId")).isFalse();

        HttpEntity<Void> bearerRequest = new HttpEntity<>(bearerHeaders(token));
        ResponseEntity<JsonNode> context = restTemplate.exchange(
                "/iam.auth/context", HttpMethod.GET, bearerRequest, JsonNode.class);
        assertThat(context.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(context.getBody()).isNotNull();
        assertThat(context.getBody().path("userId").asText())
                .isEqualTo(UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID);

        ResponseEntity<JsonNode> menus = restTemplate.exchange(
                "/platform.menu/mine", HttpMethod.GET, bearerRequest, JsonNode.class);
        assertThat(menus.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(menus.getBody()).isNotNull();
        assertThat(menus.getBody().path("code").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertThat(menus.getBody().path("message").asText()).isEqualTo("password change required");

        ResponseEntity<Void> logout = restTemplate.exchange(
                "/iam.auth/logout", HttpMethod.POST, bearerRequest, Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldRestrictRuntimeLogEndpointsToTheSystemApplicationThroughRealHttp() {
        String systemToken = issueSuperAdminSessionToken();
        String tenantToken = null;
        try {
            ResponseEntity<JsonNode> systemSchema = restTemplate.exchange(
                    "/platform.runtime_log/query/schema", HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(systemToken)), JsonNode.class);
            assertThat(systemSchema.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(systemSchema.getBody()).isNotNull();
            assertThat(systemSchema.getBody().path("quickSearch").path("fields").get(0).asText())
                    .isEqualTo("name");

            String suffix = Long.toUnsignedString(System.nanoTime(), 36);
            String tenantId = insertActiveTenant("runtime_log_" + suffix);
            String userId = "runtime_log_user_" + suffix;
            insertUser(tenantId, userId, "runtime_log_reader");
            jdbcTemplate.update("update iam_user set password_status = ? where id = ?", "NORMAL", userId);
            tenantToken = issueActiveSessionToken(tenantId, userId, "runtime_log_reader");
            HttpHeaders headers = bearerHeaders(tenantToken);
            headers.set(HttpHeaders.ACCEPT, "application/json, text/event-stream, application/octet-stream");

            for (var endpoint : Map.of(
                    "/query/schema", HttpMethod.GET,
                    "/query", HttpMethod.POST,
                    "/files/application.log/download", HttpMethod.GET,
                    "/active/stream", HttpMethod.POST).entrySet()) {
                ResponseEntity<JsonNode> denied = restTemplate.exchange(
                        "/platform.runtime_log" + endpoint.getKey(), endpoint.getValue(),
                        new HttpEntity<>(headers), JsonNode.class);
                assertThat(denied.getStatusCode()).as(endpoint.getKey()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(denied.getBody()).isNotNull();
                assertThat(denied.getBody().path("code").asText()).as(endpoint.getKey())
                        .isEqualTo("APPLICATION_NOT_OPENED");
            }
        } finally {
            if (tenantToken != null) {
                userSessionService.logout(tenantToken);
            }
            userSessionService.logout(systemToken);
        }
    }

    @Test
    void shouldDownloadRuntimeLogThroughTheRealHttpResponsePipeline() throws Exception {
        String marker = "runtime-download-" + Long.toUnsignedString(System.nanoTime(), 36);
        Files.writeString(RUNTIME_LOG_DIRECTORY.resolve("http-download.log"), marker);
        String token = issueSuperAdminSessionToken();
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    "/platform.runtime_log/files/http-download.log/download", HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(token)), byte[].class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
            assertThat(response.getBody()).isNotNull();
            assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(marker);
        } finally {
            userSessionService.logout(token);
        }
    }

    @Test
    void shouldDeliverRoleScopeFormDefaultsAsWireCodesAndEnforceTheSelectedScopeOnCreate() {
        String token = issueSuperAdminSessionToken();
        try {
            String suffix = Long.toUnsignedString(System.nanoTime(), 36);
            String tenantId = insertActiveTenant("role_scope_" + suffix);
            String organizationId = "role_scope_org_" + suffix;
            insertOrganization(tenantId, organizationId, "ROLE-SCOPE-" + suffix, "Role scope organization");

            ResponseEntity<JsonNode> context = restTemplate.exchange("/platform.module/iam.role/context", HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(token)), JsonNode.class);
            assertThat(context.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(context.getBody()).isNotNull();
            List<String> formDefaultBindings = StreamSupport.stream(context.getBody().path("uiDescriptor").path("page")
                            .path("navigator").path("contextBindings").spliterator(), false)
                    .filter(binding -> "RESOLVED_SELECTION".equals(binding.path("source").asText()))
                    .filter(binding -> "roleScope".equals(binding.path("sourceKey").asText()))
                    .filter(binding -> "FORM_DEFAULT".equals(binding.path("target").asText()))
                    .map(binding -> binding.path("targetKey").asText())
                    .toList();
            assertThat(formDefaultBindings).containsExactly("ownerScopeType", "ownerScopeId", "ownerScopeKey");

            JsonNode platform = roleScopeFormDefaults(token, "platform");
            assertThat(platform.path("ownerScopeType").asText()).isEqualTo("platform");
            assertThat(platform.has("ownerScopeId")).isTrue();
            assertThat(platform.path("ownerScopeId").isNull()).isTrue();
            assertThat(platform.path("ownerScopeKey").asText()).isEqualTo("platform");

            JsonNode tenant = roleScopeFormDefaults(token, "tenant:" + tenantId);
            assertThat(tenant.path("ownerScopeType").asText()).isEqualTo("tenant");
            assertThat(tenant.path("ownerScopeId").asText()).isEqualTo(tenantId);
            assertThat(tenant.path("ownerScopeKey").asText()).isEqualTo("tenant:" + tenantId);

            JsonNode organization = roleScopeFormDefaults(token, "organization:" + organizationId);
            assertThat(organization.path("ownerScopeType").asText()).isEqualTo("organization");
            assertThat(organization.path("ownerScopeId").asText()).isEqualTo(organizationId);
            assertThat(organization.path("ownerScopeKey").asText())
                    .isEqualTo("organization:" + organizationId);

            Map<String, Object> role = new java.util.LinkedHashMap<>();
            role.put("title", "Scope constrained role " + suffix);
            role.put("assignmentType", "employment");
            role.put("roleKind", "standard");
            role.put("sharePolicy", "private");
            role.put("ownerScopeType", "platform");
            role.put("ownerScopeId", "browser-supplied-owner");
            role.put("ownerScopeKey", "browser-supplied-key");

            ResponseEntity<JsonNode> created = restTemplate.exchange("/iam.role/insert", HttpMethod.POST,
                    new HttpEntity<>(role, roleScopeHeaders(token, "organization:" + organizationId)), JsonNode.class);

            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(created.getBody()).isNotNull();
            JsonNode createdRole = created.getBody().path("data");
            assertThat(createdRole.path("ownerScopeType").asText()).isEqualTo("organization");
            assertThat(createdRole.path("ownerScopeId").asText()).isEqualTo(organizationId);
            assertThat(createdRole.path("ownerScopeKey").asText())
                    .isEqualTo("organization:" + organizationId);
            assertThat(createdRole.path("tenantId").asText()).isEqualTo(tenantId);
            assertThat(jdbcTemplate.queryForObject("select owner_scope_type from iam_role where id = ?", String.class,
                    createdRole.path("id").asText())).isEqualTo("organization");
        } finally {
            userSessionService.logout(token);
        }
    }

    @Test
    void shouldServePagedLoginAuditOperatorCandidatesToAQueryOnlyTenantRole() {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        String tenantId = insertActiveTenant("login_ops_" + suffix);
        String queryOnlyUserId = "login_query_" + suffix;
        insertUser(tenantId, queryOnlyUserId, "login_candidate_query_only");
        jdbcTemplate.update("update iam_user set password_status = ? where id = ?", "NORMAL", queryOnlyUserId);
        grantLoginAuditQueryOnlyRole(tenantId, queryOnlyUserId);

        String selectedOperatorId = loginOperatorId(suffix, 24);
        for (int index = 1; index <= 24; index++) {
            String operatorId = loginOperatorId(suffix, index);
            insertLoginAuditEvent("login-candidate-" + tenantId + "-" + index, tenantId, operatorId,
                    "needle-%02d".formatted(index), Instant.parse("2030-01-01T00:00:00Z").plusSeconds(index));
        }
        String otherTenantId = insertActiveTenant("login_ops_o_" + suffix);
        String outsideOperatorId = "login_outside_" + suffix;
        insertLoginAuditEvent("login-candidate-" + otherTenantId, otherTenantId, outsideOperatorId,
                "outside-only", Instant.parse("2030-01-02T00:00:00Z"));

        HttpHeaders headers = bearerHeaders(issueActiveSessionToken(tenantId, queryOnlyUserId, "query_only"));
        Map<String, Object> secondPageRequest = Map.of(
                "page", Map.of("pageNum", 2, "pageSize", 10),
                "selectedIds", List.of(selectedOperatorId, outsideOperatorId)
        );
        ResponseEntity<JsonNode> secondPage = restTemplate.exchange(
                "/iam.login_audit_log/operator-candidates/query", HttpMethod.POST,
                new HttpEntity<>(secondPageRequest, headers), JsonNode.class);

        assertThat(secondPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondPage.getBody()).isNotNull();
        JsonNode candidates = secondPage.getBody();
        assertThat(candidates.path("total").asInt()).isEqualTo(24);
        assertThat(candidates.path("pageNum").asInt()).isEqualTo(2);
        assertThat(candidates.path("records")).hasSize(10);
        assertThat(candidates.path("records").toString()).contains(loginOperatorId(suffix, 14));
        assertThat(candidates.path("selectedRecords")).hasSize(1);
        assertThat(candidates.path("selectedRecords").get(0).path("id").asText()).isEqualTo(selectedOperatorId);
        assertThat(candidates.path("selectedRecords").toString()).doesNotContain(outsideOperatorId);

        ResponseEntity<JsonNode> keyword = restTemplate.exchange(
                "/iam.login_audit_log/operator-candidates/query", HttpMethod.POST,
                new HttpEntity<>(Map.of("keyword", "needle-01", "page", Map.of("pageNum", 1, "pageSize", 10)), headers),
                JsonNode.class);
        assertThat(keyword.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(keyword.getBody()).isNotNull();
        assertThat(keyword.getBody().path("total").asInt()).isEqualTo(1);
        assertThat(keyword.getBody().path("records").get(0).path("id").asText())
                .isEqualTo(loginOperatorId(suffix, 1));

        ResponseEntity<JsonNode> userQuery = restTemplate.exchange("/iam.user/query", HttpMethod.POST,
                new HttpEntity<>(Map.of("page", Map.of("pageNum", 1, "pageSize", 10)), headers), JsonNode.class);
        assertThat(userQuery.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> userReference = restTemplate.exchange("/iam.user/navigator/reference/query",
                HttpMethod.POST, new HttpEntity<>(Map.of("keyword", "needle", "page", Map.of("pageNum", 1, "pageSize", 10)), headers),
                JsonNode.class);
        assertThat(userReference.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> detail = restTemplate.exchange(
                "/iam.login_audit_log/login-candidate-" + tenantId + "-24", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String ungrantedUserId = "login_ungranted_" + suffix;
        insertUser(tenantId, ungrantedUserId, "login_ungranted");
        jdbcTemplate.update("update iam_user set password_status = ? where id = ?", "NORMAL", ungrantedUserId);
        ResponseEntity<JsonNode> ungrantedCandidates = restTemplate.exchange(
                "/iam.login_audit_log/operator-candidates/query", HttpMethod.POST,
                new HttpEntity<>(Map.of("page", Map.of("pageNum", 1, "pageSize", 10)),
                        bearerHeaders(issueActiveSessionToken(tenantId, ungrantedUserId, "login_ungranted"))),
                JsonNode.class);
        assertThat(ungrantedCandidates.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldOpenStaticReferenceDetailWithViewButWithoutMenuPermission() {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        String tenantId = insertActiveTenant("reference_view_" + suffix);
        String otherTenantId = insertActiveTenant("reference_view_other_" + suffix);
        String viewUserId = "rv_user_" + suffix;
        String visibleRecordId = "rv_record_" + suffix;
        String outsideRecordId = "rv_outside_" + suffix;
        insertUser(tenantId, viewUserId, "rv_user_" + suffix);
        insertUser(tenantId, visibleRecordId, "rv_record_" + suffix);
        insertUser(otherTenantId, outsideRecordId, "rv_outside_" + suffix);
        jdbcTemplate.update("update iam_user set password_status = ? where id in (?, ?, ?)", "NORMAL", viewUserId,
                visibleRecordId, outsideRecordId);
        grantTenantScopedEmploymentAction(tenantId, viewUserId, "view_" + suffix, PlatformAction.VIEW);

        HttpHeaders viewHeaders = bearerHeaders(issueActiveSessionToken(tenantId, viewUserId, "reference_view"));
        ResponseEntity<JsonNode> descriptor = restTemplate.exchange(
                "/platform.module/iam.user/view-context", HttpMethod.GET, new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(descriptor.getStatusCode()).withFailMessage("view descriptor response: %s", descriptor.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(descriptor.getBody()).isNotNull();
        assertThat(descriptor.getBody().path("moduleAlias").asText()).isEqualTo(UserAccountService.MODULE_ALIAS);
        assertThat(descriptor.getBody().path("uiDescriptor").path("moduleAlias").asText())
                .isEqualTo(UserAccountService.MODULE_ALIAS);

        ResponseEntity<JsonNode> visibleDetail = restTemplate.exchange(
                "/iam.user/view/" + visibleRecordId, HttpMethod.GET, new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(visibleDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(visibleDetail.getBody()).isNotNull();
        assertThat(visibleDetail.getBody().path("id").asText()).isEqualTo(visibleRecordId);

        ResponseEntity<JsonNode> outsideDetail = restTemplate.exchange(
                "/iam.user/view/" + outsideRecordId, HttpMethod.GET, new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(outsideDetail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> menuContext = restTemplate.exchange(
                "/platform.module/iam.user/context", HttpMethod.GET, new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(menuContext.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String sourceQueryUserId = "rsq_user_" + suffix;
        insertUser(tenantId, sourceQueryUserId, "rsq_user_" + suffix);
        jdbcTemplate.update("update iam_user set password_status = ? where id = ?", "NORMAL", sourceQueryUserId);
        grantLoginAuditQueryOnlyRole(tenantId, sourceQueryUserId);
        insertLoginAuditEvent("reference-source-" + suffix, tenantId, "source_operator_" + suffix,
                "source-query", Instant.parse("2030-01-01T00:00:00Z"));
        HttpHeaders sourceQueryHeaders = bearerHeaders(issueActiveSessionToken(tenantId, sourceQueryUserId,
                "source_query"));
        ResponseEntity<JsonNode> candidates = restTemplate.exchange(
                "/iam.login_audit_log/operator-candidates/query", HttpMethod.POST,
                new HttpEntity<>(Map.of("keyword", "source-query", "page", Map.of("pageNum", 1, "pageSize", 10)),
                        sourceQueryHeaders),
                JsonNode.class);
        assertThat(candidates.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(candidates.getBody()).isNotNull();
        assertThat(candidates.getBody().path("total").asInt()).isEqualTo(1);
        assertThat(candidates.getBody().path("records").get(0).path("id").asText())
                .isEqualTo("source_operator_" + suffix);
        ResponseEntity<JsonNode> sourceQueryDescriptor = restTemplate.exchange(
                "/platform.module/iam.user/view-context", HttpMethod.GET,
                new HttpEntity<>(sourceQueryHeaders), JsonNode.class);
        assertThat(sourceQueryDescriptor.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldOpenDynamicReferenceDetailWithViewButWithoutMenuPermission() {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        String applicationAlias = "refdyn" + suffix;
        String moduleAlias = applicationAlias + ".target";
        String tenantId = insertActiveTenant("dynamic_view_" + suffix);
        String otherTenantId = insertActiveTenant("dynamic_view_other_" + suffix);
        installDynamicReferenceTarget(applicationAlias, moduleAlias, "target");
        openTenantApplication(tenantId, applicationAlias);
        String viewUserId = "rdv_user_" + suffix;
        insertUser(tenantId, viewUserId, "rdv_user_" + suffix);
        jdbcTemplate.update("update iam_user set password_status = ? where id = ?", "NORMAL", viewUserId);
        String visibleRecordId = insertDynamicReferenceTarget(moduleAlias, "target", tenantId, viewUserId,
                "Visible dynamic target " + suffix);
        String hiddenRecordId = insertDynamicReferenceTarget(moduleAlias, "target", tenantId, "other_user_" + suffix,
                "Hidden dynamic target " + suffix);
        String outsideRecordId = insertDynamicReferenceTarget(moduleAlias, "target", otherTenantId, viewUserId,
                "Outside dynamic target " + suffix);
        assertThat(jdbcTemplate.queryForObject("select auth_user_id from " + applicationAlias + "_target where id = ?",
                String.class, visibleRecordId)).isEqualTo(viewUserId);
        assertThat(jdbcTemplate.queryForObject("select auth_user_id from " + applicationAlias + "_target where id = ?",
                String.class, hiddenRecordId)).isEqualTo("other_user_" + suffix);
        grantTenantScopedEmploymentAction(tenantId, viewUserId, "dynamic_" + suffix, moduleAlias,
                PlatformAction.VIEW, DataScopePolicy.OWNER);
        try (CurrentUserContext.Scope user = CurrentUserContext.use(
                CurrentUser.tenantUser(viewUserId, "rdv_user_" + suffix, tenantId));
             TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            assertThat(dynamicRecordService.select(moduleAlias, "target", visibleRecordId)).isNotNull();
            assertThat(dynamicRecordService.select(moduleAlias, "target", hiddenRecordId)).isNull();
        }

        HttpHeaders viewHeaders = bearerHeaders(issueActiveSessionToken(tenantId, viewUserId, "dynamic_view"));
        ResponseEntity<JsonNode> descriptor = restTemplate.exchange(
                "/platform.module/" + moduleAlias + "/view-context", HttpMethod.GET,
                new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(descriptor.getStatusCode()).withFailMessage("dynamic descriptor response: %s", descriptor.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(descriptor.getBody()).isNotNull();
        assertThat(descriptor.getBody().path("moduleAlias").asText()).isEqualTo(moduleAlias);
        assertThat(descriptor.getBody().path("mainEntityAlias").asText()).isEqualTo("target");
        assertThat(descriptor.getBody().path("capabilities").toString()).contains("DATA_SCOPE");

        ResponseEntity<JsonNode> visibleDetail = restTemplate.exchange(
                "/" + moduleAlias + "/view/" + visibleRecordId, HttpMethod.GET,
                new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(visibleDetail.getStatusCode()).withFailMessage("dynamic detail response: %s", visibleDetail.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(visibleDetail.getBody()).isNotNull();
        assertThat(visibleDetail.getBody().path("id").asText()).isEqualTo(visibleRecordId);
        assertThat(visibleDetail.getBody().path("values").path("title").asText())
                .isEqualTo("Visible dynamic target " + suffix);

        ResponseEntity<JsonNode> hiddenDetail = restTemplate.exchange(
                "/" + moduleAlias + "/view/" + hiddenRecordId, HttpMethod.GET,
                new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(hiddenDetail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> outsideDetail = restTemplate.exchange(
                "/" + moduleAlias + "/view/" + outsideRecordId, HttpMethod.GET,
                new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(outsideDetail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> missingDetail = restTemplate.exchange(
                "/" + moduleAlias + "/view/missing-" + suffix, HttpMethod.GET,
                new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(missingDetail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> menuContext = restTemplate.exchange(
                "/platform.module/" + moduleAlias + "/context", HttpMethod.GET,
                new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(menuContext.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> readonlyActions = restTemplate.exchange(
                "/" + moduleAlias + "/actions/" + visibleRecordId, HttpMethod.GET,
                new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(readonlyActions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Iterable<JsonNode>) readonlyActions.getBody().path("actions")).anySatisfy(action -> {
            assertThat(action.path("actionCode").asText()).isEqualTo("update");
            assertThat(action.path("available").asBoolean()).isFalse();
        });

        Map<String, Object> update = Map.of("version", visibleDetail.getBody().path("version").asInt(),
                "values", Map.of("title", "Updated reference target " + suffix));
        ResponseEntity<JsonNode> deniedUpdate = restTemplate.exchange(
                "/" + moduleAlias + "/update/" + visibleRecordId, HttpMethod.POST,
                new HttpEntity<>(update, viewHeaders), JsonNode.class);
        assertThat(deniedUpdate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            roleService.replaceDataGrantActions("rv_data_role_dynamic_" + suffix, List.of(
                    new RoleService.DataGrantActionCommand(PlatformAction.VIEW.code(), DataScopePolicy.OWNER, true),
                    new RoleService.DataGrantActionCommand(PlatformAction.UPDATE.code(), DataScopePolicy.OWNER, true)));
            roleService.grantAction("rv_act_role_dynamic_" + suffix, moduleAlias, PlatformAction.UPDATE.code(),
                    DataScopePolicy.INHERIT_DATA_GRANT, TenantScopePolicy.CURRENT_TENANT);
        }
        ResponseEntity<JsonNode> writableActions = restTemplate.exchange(
                "/" + moduleAlias + "/actions/" + visibleRecordId, HttpMethod.GET,
                new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(writableActions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Iterable<JsonNode>) writableActions.getBody().path("actions")).anySatisfy(action -> {
            assertThat(action.path("actionCode").asText()).isEqualTo("update");
            assertThat(action.path("available").asBoolean()).isTrue();
        });
        ResponseEntity<JsonNode> updated = restTemplate.exchange(
                "/" + moduleAlias + "/update/" + visibleRecordId, HttpMethod.POST,
                new HttpEntity<>(update, viewHeaders), JsonNode.class);
        assertThat(updated.getStatusCode()).withFailMessage("update without menu response: %s", updated.getBody())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> reloaded = restTemplate.exchange(
                "/" + moduleAlias + "/view/" + visibleRecordId, HttpMethod.GET,
                new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(reloaded.getBody().path("values").path("title").asText())
                .isEqualTo("Updated reference target " + suffix);
        assertThat(reloaded.getBody().path("version").asInt())
                .isGreaterThan(visibleDetail.getBody().path("version").asInt());
        ResponseEntity<JsonNode> staleUpdate = restTemplate.exchange(
                "/" + moduleAlias + "/update/" + visibleRecordId, HttpMethod.POST,
                new HttpEntity<>(update, viewHeaders), JsonNode.class);
        assertThat(staleUpdate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<JsonNode> hiddenUpdate = restTemplate.exchange(
                "/" + moduleAlias + "/update/" + hiddenRecordId, HttpMethod.POST,
                new HttpEntity<>(update, viewHeaders), JsonNode.class);
        assertThat(hiddenUpdate.getStatusCode()).withFailMessage("hidden update: %s", hiddenUpdate.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject("select title from " + applicationAlias + "_target where id = ?",
                String.class, hiddenRecordId)).isEqualTo("Hidden dynamic target " + suffix);
        ResponseEntity<JsonNode> stillNoMenu = restTemplate.exchange(
                "/platform.module/" + moduleAlias + "/context", HttpMethod.GET,
                new HttpEntity<>(viewHeaders), JsonNode.class);
        assertThat(stillNoMenu.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldManageRecordPermissionsThroughStandardHttpEndpoints() {
        String tenantId = insertActiveTenant("tenant_permission_http");
        seedUserEmployeeProjectionRecords(tenantId);
        jdbcTemplate.update("update iam_user set password_status = ? where tenant_id = ?", "NORMAL", tenantId);
        String id = projectionUserId(tenantId, "alice");
        String targetId = projectionUserId(tenantId, "bob");
        HttpHeaders headers = bearerHeaders(issueSuperAdminSessionToken());
        ResponseEntity<JsonNode> snapshot = restTemplate.exchange("/iam.user/permissions/" + id,
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(snapshot.getStatusCode()).withFailMessage("snapshot: %s", snapshot.getBody()).isEqualTo(HttpStatus.OK);
        int version = snapshot.getBody().path("version").asInt();
        String command = "{\"version\":" + version + ",\"operation\":\"ADD\",\"relation\":\"MEMBER\",\"userIds\":[\"" + targetId + "\"]}";
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> changed = restTemplate.exchange("/iam.user/permissions/" + id,
                HttpMethod.POST, new HttpEntity<>(command, headers), JsonNode.class);
        assertThat(changed.getStatusCode()).withFailMessage("change: %s", changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("select auth_member_ids from iam_user where id = ?", String.class, id)).isEqualTo(targetId);
        ResponseEntity<JsonNode> stale = restTemplate.exchange("/iam.user/permissions/" + id,
                HttpMethod.POST, new HttpEntity<>(command, headers), JsonNode.class);
        assertThat(stale.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void shouldQueryUserListWithBoundEmployeeProjectionThroughRealDatabase() {
        String tenantId = "tenant_projection_it";
        insertActiveTenant(tenantId);
        seedUserEmployeeProjectionRecords(tenantId);
        assertThat(staticModuleDefinitionCatalog.find(UserAccountService.MODULE_ALIAS))
                .get()
                .satisfies(definition -> {
                    assertThat(definition.entities()).isNotEmpty();
                    assertThat(definition.projectionJoins()).isEmpty();
                });
        assertThat(staticRecordReadProjectionService.supportsDefaultListQuery(
                UserAccountService.MODULE_ALIAS, userAccountService)).isTrue();

        WebPageResponse<?> firstPage = staticRecordReadProjectionService.queryDefaultList(
                UserAccountService.MODULE_ALIAS,
                Criteria.of().eq("tenantId", tenantId).eq("deleted", Boolean.FALSE),
                PageRequest.of(1, 2),
                userAccountService,
                Sort.asc("username")
        ).orElseThrow();

        assertThat(firstPage.total()).isEqualTo(4);
        assertThat(firstPage.records()).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> alice = (Map<String, Object>) firstPage.records().get(0);
        assertThat(alice)
                .containsEntry("id", projectionUserId(tenantId, "alice"))
                .containsEntry("username", projectionUsername(tenantId, "alice"))
                .containsEntry("employeeNo", "E-PROJ-001")
                .containsEntry("employeeTitle", "Alice Employee")
                .containsEntry("version", 0)
                .containsEntry("deletedAt", null);
        assertThat(alice).containsEntry("passwordStatus", "ACTIVE");
        assertThat(alice).doesNotContainKeys("tenantId", "deleted", "createdAt");
        @SuppressWarnings("unchecked")
        Map<String, Object> bob = (Map<String, Object>) firstPage.records().get(1);
        assertThat(bob)
                .containsEntry("id", projectionUserId(tenantId, "bob"))
                .containsEntry("username", projectionUsername(tenantId, "bob"));
        assertThat(bob.get("employeeNo")).isNull();
        assertThat(bob.get("employeeTitle")).isNull();

        WebPageResponse<?> secondPage = staticRecordReadProjectionService.queryDefaultList(
                UserAccountService.MODULE_ALIAS,
                Criteria.of().eq("tenantId", tenantId).eq("deleted", Boolean.FALSE),
                PageRequest.of(2, 2),
                userAccountService,
                Sort.asc("username")
        ).orElseThrow();

        assertThat(secondPage.total()).isEqualTo(4);
        assertThat(secondPage.records()).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> charlie = (Map<String, Object>) secondPage.records().get(0);
        assertThat(charlie).containsEntry("username", projectionUsername(tenantId, "charlie"));
        assertThat(charlie.get("employeeNo")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> dave = (Map<String, Object>) secondPage.records().get(1);
        assertThat(dave).containsEntry("username", projectionUsername(tenantId, "dave"));
        assertThat(dave.get("employeeNo")).isNull();

        WebPageResponse<?> sortedByEmployeeTitle = staticRecordReadProjectionService.queryDefaultList(
                UserAccountService.MODULE_ALIAS,
                Criteria.of().eq("tenantId", tenantId).eq("deleted", Boolean.FALSE),
                PageRequest.of(1, 4),
                userAccountService,
                Sort.asc("employeeTitle")
        ).orElseThrow();

        assertThat(sortedByEmployeeTitle.records()).hasSize(4);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstByEmployeeTitle = (Map<String, Object>) sortedByEmployeeTitle.records().getFirst();
        assertThat(firstByEmployeeTitle)
                .containsEntry("username", projectionUsername(tenantId, "alice"))
                .containsEntry("employeeTitle", "Alice Employee");

        WebPageResponse<?> filteredByEmployeeNo = staticRecordReadProjectionService.queryDefaultList(
                UserAccountService.MODULE_ALIAS,
                Criteria.of()
                        .eq("tenantId", tenantId)
                        .eq("deleted", Boolean.FALSE)
                        .eq("employeeNo", "E-PROJ-001"),
                PageRequest.of(1, 20),
                userAccountService,
                Sort.asc("username")
        ).orElseThrow();

        assertThat(filteredByEmployeeNo.records()).singleElement()
                .satisfies(record -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> output = (Map<String, Object>) record;
                    assertThat(output)
                            .containsEntry("username", projectionUsername(tenantId, "alice"))
                            .containsEntry("employeeNo", "E-PROJ-001");
                });
        assertThatThrownBy(() -> staticRecordReadProjectionService.queryDefaultList(
                UserAccountService.MODULE_ALIAS,
                Criteria.of()
                        .eq("tenantId", tenantId)
                        .eq("deleted", Boolean.FALSE)
                        .eq("employeeTitle", "Alice Employee"),
                PageRequest.of(1, 20),
                userAccountService
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projection query field is not projected: employeeTitle");

        String token = issueSuperAdminSessionToken();
        HttpHeaders headers = bearerHeaders(token);
        WebQueryRequest httpFilterRequest = new WebQueryRequest(
                new WebPageRequest(1, 20),
                List.of(
                        new WebQueryCondition("tenantId", "EQ", List.of(tenantId)),
                        new WebQueryCondition("employeeNo", "EQ", List.of("E-PROJ-001"))
                ),
                List.of(new WebSort("employeeTitle", false))
        );
        httpFilterRequest = httpFilterRequest.withExternalQueryValues(Map.of("tenantId", tenantId));
        ResponseEntity<JsonNode> httpFiltered = restTemplate.exchange(
                "/iam.user/query", HttpMethod.POST, new HttpEntity<>(httpFilterRequest, headers), JsonNode.class);

        assertThat(httpFiltered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(httpFiltered.getBody()).isNotNull();
        assertThat(httpFiltered.getBody().path("records")).hasSize(1);
        JsonNode httpAlice = httpFiltered.getBody().path("records").get(0);
        assertThat(httpAlice.path("username").asText()).isEqualTo(projectionUsername(tenantId, "alice"));
        assertThat(httpAlice.path("employeeNo").asText()).isEqualTo("E-PROJ-001");
        assertThat(httpAlice.path("employeeTitle").asText()).isEqualTo("Alice Employee");

        ResponseEntity<JsonNode> querySchema = restTemplate.exchange(
                "/iam.user/query/schema", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(querySchema.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(querySchema.getBody()).isNotNull();
        JsonNode schemaFields = querySchema.getBody().path("fields");
        JsonNode employeeNoSchema = fieldSchema(schemaFields, "employeeNo");
        assertThat(employeeNoSchema.path("operators")).isNotEmpty();
        assertThat(employeeNoSchema.path("sortable").asBoolean()).isTrue();
        JsonNode employeeTitleSchema = fieldSchema(schemaFields, "employeeTitle");
        assertThat(employeeTitleSchema.path("operators")).isEmpty();
        assertThat(employeeTitleSchema.path("sortable").asBoolean()).isTrue();

        WebPageResponse<?> employeePage = staticRecordReadProjectionService.queryDefaultList(
                EmployeeService.MODULE_ALIAS,
                Criteria.of().eq("tenantId", tenantId).eq("deleted", Boolean.FALSE),
                PageRequest.of(1, 20),
                employeeService,
                Sort.asc("employeeNo")
        ).orElseThrow();

        assertThat(employeePage.records()).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> employeeAlice = (Map<String, Object>) employeePage.records().get(0);
        assertThat(employeeAlice)
                .containsEntry("employeeNo", "E-PROJ-001")
                .containsEntry("username", projectionUsername(tenantId, "alice"))
                .containsEntry("accountBound", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> employeeCharlie = (Map<String, Object>) employeePage.records().get(1);
        assertThat(employeeCharlie)
                .containsEntry("employeeNo", "E-PROJ-003")
                .containsEntry("accountBound", false);
        assertThat(employeeCharlie.get("username")).isNull();

        WebPageResponse<?> unboundEmployees = staticRecordReadProjectionService.queryDefaultList(
                EmployeeService.MODULE_ALIAS,
                Criteria.of()
                        .eq("tenantId", tenantId)
                        .eq("deleted", Boolean.FALSE)
                        .eq("accountBound", Boolean.FALSE),
                PageRequest.of(1, 20),
                employeeService,
                Sort.asc("employeeNo")
        ).orElseThrow();

        assertThat(unboundEmployees.records()).singleElement()
                .satisfies(record -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> output = (Map<String, Object>) record;
                    assertThat(output)
                            .containsEntry("employeeNo", "E-PROJ-003")
                            .containsEntry("accountBound", false);
                });

        WebQueryRequest employeeRequest = new WebQueryRequest(new WebPageRequest(1, 20),
                List.of(new WebQueryCondition("username", "EQ", List.of(projectionUsername(tenantId, "alice")))),
                List.of(new WebSort("employeeNo", false)))
                .withExternalQueryValues(Map.of("organizationId", projectionOrganizationId(tenantId)));
        HttpHeaders employeeHeaders = bearerHeaders(token);
        employeeHeaders.set("X-MuYun-Tenant-Id", tenantId);
        ResponseEntity<JsonNode> employeeHttpPage = restTemplate.exchange(
                "/iam.employee/query", HttpMethod.POST, new HttpEntity<>(employeeRequest, employeeHeaders), JsonNode.class);
        assertThat(employeeHttpPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(employeeHttpPage.getBody()).isNotNull();
        assertThat(employeeHttpPage.getBody().path("records")).hasSize(1);
        JsonNode employeeHttpAlice = employeeHttpPage.getBody().path("records").get(0);
        assertThat(employeeHttpAlice.path("employeeNo").asText()).isEqualTo("E-PROJ-001");
        assertThat(employeeHttpAlice.path("username").asText()).isEqualTo(projectionUsername(tenantId, "alice"));
        assertThat(employeeHttpAlice.path("accountBound").asBoolean()).isTrue();

        ResponseEntity<JsonNode> employeeQuerySchema = restTemplate.exchange(
                "/iam.employee/query/schema", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(employeeQuerySchema.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(employeeQuerySchema.getBody()).isNotNull();
        JsonNode employeeSchemaFields = employeeQuerySchema.getBody().path("fields");
        JsonNode usernameSchema = fieldSchema(employeeSchemaFields, "username");
        assertThat(usernameSchema.path("operators")).isNotEmpty();
        JsonNode accountBoundSchema = fieldSchema(employeeSchemaFields, "accountBound");
        assertThat(accountBoundSchema.path("valueType").asText()).isEqualTo("BOOLEAN");
        assertThat(accountBoundSchema.path("operators")).isNotEmpty();

        WebQueryRequest httpRejectRequest = new WebQueryRequest(
                new WebPageRequest(1, 20),
                List.of(
                        new WebQueryCondition("tenantId", "EQ", List.of(tenantId)),
                        new WebQueryCondition("employeeTitle", "EQ", List.of("Alice Employee"))
                ),
                List.of()
        );
        httpRejectRequest = httpRejectRequest.withExternalQueryValues(Map.of("tenantId", tenantId));
        ResponseEntity<JsonNode> httpRejected = restTemplate.exchange(
                "/iam.user/query", HttpMethod.POST, new HttpEntity<>(httpRejectRequest, headers), JsonNode.class);

        assertThat(httpRejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(httpRejected.getBody()).isNotNull();
        assertThat(httpRejected.getBody().path("message").asText())
                .contains("query operator is not supported by iam.user: employeeTitle.EQ");
    }

    @Test
    void shouldQueryUserSelectorWithEmployeeOrganizationAndDepartmentProjectionThroughRealDatabase() {
        String tenantId = "tenant_selector_projection_it";
        seedUserEmployeeProjectionRecords(tenantId);
        String token = issueSuperAdminSessionToken();
        HttpHeaders headers = bearerHeaders(token);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/iam.user/selector/query",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "keyword", projectionUsername(tenantId, "alice"),
                        "page", Map.of("pageNum", 1, "pageSize", 20)
                ), headers),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("total").asLong()).isEqualTo(1);
        JsonNode records = response.getBody().path("records");
        assertThat(records).hasSize(1);
        JsonNode alice = records.get(0);
        assertThat(alice.path("id").asText()).isEqualTo(projectionUserId(tenantId, "alice"));
        assertThat(alice.path("username").asText()).isEqualTo(projectionUsername(tenantId, "alice"));
        assertThat(alice.path("employeeId").asText()).isEqualTo(projectionEmployeeId(tenantId, "alice"));
        assertThat(alice.path("employeeNo").asText()).isEqualTo("E-PROJ-001");
        assertThat(alice.path("employeeTitle").asText()).isEqualTo("Alice Employee");
        assertThat(alice.path("organizationId").asText()).isEqualTo(projectionOrganizationId(tenantId));
        assertThat(alice.path("organizationTitle").asText()).isEqualTo("Projection Organization");
        assertThat(alice.path("departmentId").asText()).isEqualTo(projectionDepartmentId(tenantId));
        assertThat(alice.path("departmentTitle").asText()).isEqualTo("Projection Department");
    }

    private JsonNode fieldSchema(JsonNode fields, String name) {
        for (JsonNode field : fields) {
            if (name.equals(field.path("name").asText())) {
                return field;
            }
        }
        throw new AssertionError("missing query schema field: " + name);
    }

    @Test
    void contributorCountMatchesListScopeForNavigationAndAdditionalConditions() {
        String fixtureKey = "sum_" + Long.toUnsignedString(System.nanoTime(), 36);
        String alias = fixtureKey;
        String tenantId = insertActiveTenant(alias);
        String otherTenantId = insertActiveTenant(alias + "_other");
        String aliceId = fixtureKey + "_alice";
        String bobId = fixtureKey + "_bob";
        String offlineId = fixtureKey + "_offline";
        String otherTenantUserId = fixtureKey + "_other";
        insertUser(tenantId, aliceId, aliceId);
        insertUser(tenantId, bobId, bobId);
        insertUser(tenantId, offlineId, offlineId);
        insertUser(otherTenantId, otherTenantUserId, otherTenantUserId);
        insertActiveSession(tenantId, aliceId, "alice");
        insertActiveSession(tenantId, bobId, "bob");
        insertActiveSession(otherTenantId, otherTenantUserId, "other");

        HttpHeaders headers = bearerHeaders(issueSuperAdminSessionToken());

        JsonNode navigationOnly = querySummaryRecords(headers, tenantId, List.of());
        assertListAndSummary(navigationOnly, 3, 2);

        JsonNode ordinaryCondition = querySummaryRecords(headers, tenantId,
                List.of(summaryCondition("username", "EQ", aliceId)));
        assertListAndSummary(ordinaryCondition, 1, 1);

        JsonNode offlineCondition = querySummaryRecords(headers, tenantId,
                List.of(summaryCondition("username", "EQ", offlineId)));
        assertListAndSummary(offlineCondition, 1, 0);
    }

    private JsonNode querySummaryRecords(HttpHeaders headers, String tenantId, List<Map<String, Object>> conditions) {
        Map<String, Object> request = Map.of(
                "page", Map.of("pageNum", 1, "pageSize", 20),
                "conditions", conditions,
                "sorts", List.of(),
                "externalQueryValues", Map.of("tenantId", tenantId));
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/iam.user/query", HttpMethod.POST, new HttpEntity<>(request, headers), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void assertListAndSummary(JsonNode response, int records, int activeUsers) {
        assertThat(response.path("total").asInt()).isEqualTo(records);
        assertThat(response.path("records")).hasSize(records);
        JsonNode summary = null;
        for (JsonNode item : response.path("summaries")) {
            if ("onlineUsers".equals(item.path("key").asText())) {
                summary = item;
                break;
            }
        }
        assertThat(summary).isNotNull();
        assertThat(summary.path("value").asInt()).isEqualTo(activeUsers);
    }

    private String insertActiveTenant(String tenantId) {
        try (TenantContext.Scope ignored = TenantContext.system("active tenant integration fixture")) {
            Tenant tenant = new Tenant();
            tenant.setAlias(tenantId);
            tenant.setTitle(tenantId);
            tenant.setEnabled(Boolean.TRUE);
            tenantService.insert(tenant);
            return tenant.getId();
        }
    }

    private Map<String, Object> summaryCondition(String field, String operator, String value) {
        return Map.of("fieldName", field, "operator", operator, "values", List.of(value));
    }

    private void insertActiveSession(String tenantId, String userId, String code) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String token = "summary-token-" + tenantId + "-" + code;
        jdbcTemplate.update("""
                        insert into iam_user_session (
                            id, tenant_id, user_id, username, token_hash, issued_at, expires_at,
                            max_expires_at, last_seen_at, password_change_required, deleted
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                java.util.UUID.randomUUID().toString().replace("-", ""), tenantId, userId, code, tokenHash(token),
                Timestamp.from(now), Timestamp.from(now.plus(1, ChronoUnit.HOURS)),
                Timestamp.from(now.plus(1, ChronoUnit.DAYS)), Timestamp.from(now), Boolean.FALSE, Boolean.FALSE);
    }

    private String issueActiveSessionToken(String tenantId, String userId, String username) {
        String token = "login-candidate-token-" + System.nanoTime();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        jdbcTemplate.update("""
                        insert into iam_user_session (
                            id, tenant_id, user_id, username, token_hash, issued_at, expires_at,
                            max_expires_at, last_seen_at, password_change_required, deleted
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                java.util.UUID.randomUUID().toString().replace("-", ""), tenantId, userId, username, tokenHash(token),
                Timestamp.from(now), Timestamp.from(now.plus(1, ChronoUnit.HOURS)),
                Timestamp.from(now.plus(1, ChronoUnit.DAYS)), Timestamp.from(now), Boolean.FALSE, Boolean.FALSE);
        return token;
    }

    private void grantLoginAuditQueryOnlyRole(String tenantId, String userId) {
        String roleId = "login_role_" + userId.substring("login_query_".length());
        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            Role role = new Role();
            role.setId(roleId);
            role.setTitle("Login audit query only");
            role.setAssignmentType(RoleAssignmentType.ACCOUNT);
            role.setRoleKind(RoleKind.STANDARD);
            role.setOwnerScopeType(RoleOwnerScopeType.TENANT);
            role.setOwnerScopeId(tenantId);
            role.setOwnerScopeKey("tenant:" + tenantId);
            role.setSharePolicy(RoleSharePolicy.PRIVATE);
            role.setEnabled(Boolean.TRUE);
            roleService.insert(role);
            roleService.grantAction(roleId, LoginAuditGovernanceService.MODULE_ALIAS,
                    LoginAuditGovernanceService.QUERY_ACTION_CODE);
            roleService.grantAccountRoleResult(roleId, userId, tenantId);
        }
    }

    private void installDynamicReferenceTarget(String applicationAlias, String moduleAlias, String entityAlias) {
        try (CurrentUserContext.Scope user = CurrentUserContext.use(
                CurrentUser.systemUser("dynamic-reference-fixture", "Dynamic reference fixture"));
             TenantContext.Scope ignored = TenantContext.system("install dynamic reference target fixture")) {
            transactionTemplate.executeWithoutResult(status -> {
                Application application = new Application();
                application.setAlias(applicationAlias);
                application.setTitle("Dynamic reference application");
                applicationService.insert(application);

                PlatformModule module = new PlatformModule();
                module.setAlias(moduleAlias);
                module.setApplicationAlias(applicationAlias);
                module.setModuleKind(ModuleKind.DYNAMIC);
                module.setTitle("Dynamic reference target");
                moduleService.insert(module);
                moduleActionRegistrar.registerAll(List.of(PlatformAction.MENU, PlatformAction.CREATE, PlatformAction.VIEW, PlatformAction.UPDATE)
                        .stream()
                        .map(action -> dynamicStandardAction(moduleAlias, entityAlias, action))
                        .toList());

                ModuleMainMetadataCreationResult main = metadataOrchestrationService.createMainMetadata(moduleAlias,
                        new ModuleMainMetadataCreateCommand(entityAlias, "Dynamic reference target", null,
                                applicationAlias + "_" + entityAlias, true));
                MetadataField title = new MetadataField();
                title.setFieldName("title");
                title.setColumnName("title");
                title.setFieldSpecAlias("string");
                title.setFieldOwnership(MetadataFieldOwnership.BUSINESS);
                title.setFieldForm(MetadataFieldForm.PHYSICAL);
                title.setTitle("Title");
                title.setRequired(Boolean.FALSE);
                title.setTitleField(Boolean.TRUE);
                title.setEnabled(Boolean.TRUE);
                MetadataRelationChangeSetPreviewCommand proposal = new MetadataRelationChangeSetPreviewCommand(
                        main.metadata().getVersion(), Map.of(), List.of(new MetadataFieldChangeSetDraft(
                                MetadataFieldChangeSetDraft.Operation.ADD, null, null, title)));
                MetadataRelationChangeSetPreview preview = metadataChangeSetPreviewService.preview(moduleAlias,
                        main.relation().getId(), proposal);
                if (!preview.valid()) {
                    throw new IllegalStateException("dynamic reference target metadata proposal is invalid: "
                            + preview.errors());
                }
                metadataChangeSetApplyService.apply(moduleAlias, main.relation().getId(),
                        new MetadataRelationChangeSetApplyCommand(proposal, preview.proposalFingerprint()));

            });
            dynamicRuntimeRefreshService.refresh(moduleAlias);
            publishDynamicReferenceTargetPage(moduleAlias);
        }
    }

    private void publishDynamicReferenceTargetPage(String moduleAlias) {
        ModuleMetadataRelation mainRelation = moduleMetadataRelationService.list(
                Criteria.of().eq("moduleAlias", moduleAlias).eq("relationRole", RelationRole.MAIN), ALL).getFirst();
        PlatformPageDefinition page = new PlatformPageDefinition();
        page.setModuleAlias(moduleAlias);
        page.setAlias("management");
        page.setTitle("Dynamic reference target page");
        page.setContractType(PlatformPageContractType.MANAGEMENT);
        page.setMainRelationId(mainRelation.getId());
        String pageId = pageDefinitionService.insert(page);

        PlatformPresentationVariant variant = new PlatformPresentationVariant();
        variant.setPageId(pageId);
        variant.setClientType(PlatformPresentationClientType.WEB);
        variant.setScopeType(PlatformPresentationScopeType.GLOBAL);
        variant.setTitle("Dynamic reference target Web presentation");
        String variantId = presentationVariantService.insert(variant);

        PlatformPresentationRevision revision = new PlatformPresentationRevision();
        revision.setVariantId(variantId);
        revision.setRevisionNo(1);
        revision.setTemplateAlias("management");
        revision.setTemplateVersion(1);
        revision.setStatus(PlatformPresentationRevisionStatus.DRAFT);
        revision.setTitle("Dynamic reference target page v1");
        revision.setUiTreeJson("""
                {"template":"management","templateVersion":1,
                 "nodes":[
                   {"slot":"list","title":"Dynamic targets","fields":["title"]},
                   {"slot":"form","title":"Dynamic target detail","fields":["title"]}
                 ]}
                """);
        presentationRevisionPublishService.publish(presentationRevisionService.insert(revision));
    }

    private void openTenantApplication(String tenantId, String applicationAlias) {
        try (TenantContext.Scope ignored = TenantContext.system("open dynamic reference fixture application")) {
            Set<String> applications = new LinkedHashSet<>(tenantApplicationService.openedApplicationAliases(tenantId));
            applications.add(applicationAlias);
            tenantApplicationService.configureApplications(tenantId, applications);
        }
    }

    private ModuleActionContribution dynamicStandardAction(String moduleAlias,
                                                            String entityAlias,
                                                            PlatformAction action) {
        return new ModuleActionContribution(
                moduleAlias,
                entityAlias,
                action.code(),
                action.permissionActionCode(),
                action.title(),
                null,
                null,
                null,
                action.actionAuth(),
                action.dataAuth(),
                action.defaultGrantPolicy(),
                null,
                null,
                null,
                null,
                ModuleActionSourceType.DYNAMIC_MODULE,
                moduleAlias,
                null,
                null,
                null,
                null,
                true
        );
    }

    private String insertDynamicReferenceTarget(String moduleAlias,
                                                String entityAlias,
                                                String tenantId,
                                                String ownerUserId,
                                                String title) {
        try (CurrentUserContext.Scope user = CurrentUserContext.use(
                CurrentUser.systemUser("dynamic-reference-fixture", "Dynamic reference fixture"));
             TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            DynamicRecord record = dynamicRecordService.newRecord(moduleAlias, entityAlias).setValue("title", title);
            record.setAuthUserId(ownerUserId);
            return dynamicRecordService.mainEntity(moduleAlias).create(record);
        }
    }

    private void grantTenantScopedEmploymentAction(String tenantId,
                                                   String userId,
                                                   String roleSuffix,
                                                   PlatformAction action) {
        grantTenantScopedEmploymentAction(tenantId, userId, roleSuffix, UserAccountService.MODULE_ALIAS, action);
    }

    private void grantTenantScopedEmploymentAction(String tenantId,
                                                   String userId,
                                                   String roleSuffix,
                                                   String moduleAlias,
                                                   PlatformAction action) {
        grantTenantScopedEmploymentAction(tenantId, userId, roleSuffix, moduleAlias, action, DataScopePolicy.ALL);
    }

    private void grantTenantScopedEmploymentAction(String tenantId,
                                                   String userId,
                                                   String roleSuffix,
                                                   String moduleAlias,
                                                   PlatformAction action,
                                                   DataScopePolicy dataScopePolicy) {
        String organizationId = "rv_org_" + roleSuffix;
        String departmentId = "rv_dept_" + roleSuffix;
        String employeeId = "rv_employee_" + roleSuffix;
        String positionId = "rv_position_" + roleSuffix;
        String employeePositionId = "rv_employment_" + roleSuffix;
        // iam_role.id is varchar(32); the dynamic-reference fixture suffix can
        // otherwise make the descriptive prefix exceed that database contract.
        String actionRoleId = "rv_act_role_" + roleSuffix;
        String dataRoleId = "rv_data_role_" + roleSuffix;
        insertOrganization(tenantId, organizationId, "REF-ORG-" + roleSuffix, "Reference view organization");
        insertDepartment(tenantId, departmentId, organizationId, "REF-DEPT-" + roleSuffix,
                "Reference view department");
        insertEmployee(tenantId, employeeId, organizationId, departmentId, "REF-EMP-" + roleSuffix,
                "Reference view employee", false);
        insertEmployeeAccount(tenantId, "rv_binding_" + roleSuffix, employeeId, userId, false);
        jdbcTemplate.update("""
                        insert into iam_employee_position (
                            id, tenant_id, employee_id, organization_id, department_id, position_id,
                            primary_position, enabled, deleted
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                employeePositionId, tenantId, employeeId, organizationId, departmentId, positionId,
                Boolean.TRUE, Boolean.TRUE, Boolean.FALSE);
        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            Role dataRole = employmentRole(dataRoleId, tenantId, "Reference data " + action.code(), RoleKind.DATA_GRANT);
            roleService.insert(dataRole);
            roleService.replaceDataGrantActions(dataRoleId, List.of(
                    new RoleService.DataGrantActionCommand(action.code(), dataScopePolicy, true)));
            roleService.grantEmploymentRole(dataRoleId, employeePositionId);

            Role actionRole = employmentRole(actionRoleId, tenantId, "Reference action " + action.code(),
                    RoleKind.STANDARD);
            roleService.insert(actionRole);
            roleService.grantAction(actionRoleId, moduleAlias, action.code(),
                    DataScopePolicy.INHERIT_DATA_GRANT,
                    TenantScopePolicy.CURRENT_TENANT);
            roleService.grantEmploymentRole(actionRoleId, employeePositionId);
        }
    }

    private Role employmentRole(String roleId, String tenantId, String title, RoleKind roleKind) {
        Role role = new Role();
        role.setId(roleId);
        role.setTitle(title);
        role.setAssignmentType(RoleAssignmentType.EMPLOYMENT);
        role.setRoleKind(roleKind);
        role.setOwnerScopeType(RoleOwnerScopeType.TENANT);
        role.setOwnerScopeId(tenantId);
        role.setOwnerScopeKey("tenant:" + tenantId);
        role.setSharePolicy(RoleSharePolicy.PRIVATE);
        role.setEnabled(Boolean.TRUE);
        return role;
    }

    private void insertLoginAuditEvent(String eventId, String tenantId, String operatorId, String account,
                                       Instant occurredAt) {
        jdbcTemplate.update("""
                        insert into muyun_log.business_log_event (
                            event_id, event_type, occurred_at, captured_at, trace_id, tenant_id, operator_id,
                            operator_account, module_alias, action_code, details_json
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                        """,
                eventId, "LOGIN", Timestamp.from(occurredAt), Timestamp.from(occurredAt), "trace-" + eventId,
                tenantId, operatorId, account, "iam.login", "login", "{}");
    }

    private String loginOperatorId(String suffix, int index) {
        return "login_operator_" + suffix + "_" + String.format("%02d", index);
    }

    private String issueSuperAdminSessionToken() {
        String token = "projection-http-token-" + System.nanoTime();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        jdbcTemplate.update("""
                        insert into iam_user_session (
                            id, user_id, username, token_hash, issued_at, expires_at,
                            max_expires_at, last_seen_at, password_change_required, deleted
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                "proj_http_" + Long.toUnsignedString(System.nanoTime(), 36),
                UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID,
                UserAccountService.PLATFORM_SUPER_ADMIN_USERNAME,
                tokenHash(token),
                Timestamp.from(now),
                Timestamp.from(now.plus(1, ChronoUnit.HOURS)),
                Timestamp.from(now.plus(1, ChronoUnit.DAYS)),
                Timestamp.from(now),
                Boolean.FALSE,
                Boolean.FALSE);
        return token;
    }

    private String tokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private JsonNode roleScopeFormDefaults(String token, String selectionKey) {
        ResponseEntity<JsonNode> response = restTemplate.exchange("/iam.role/page-context/form-defaults",
                HttpMethod.GET, new HttpEntity<>(roleScopeHeaders(token, selectionKey)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private HttpHeaders roleScopeHeaders(String token, String selectionKey) {
        HttpHeaders headers = bearerHeaders(token);
        headers.set("X-MuYun-Page-Selection", "{\"kind\":\"roleScope\",\"key\":\"" + selectionKey + "\"}");
        return headers;
    }

    private UserSession onlyActiveSession(List<UserSession> sessions) {
        List<UserSession> activeSessions = sessions.stream()
                .filter(session -> session.getRevokedAt() == null)
                .toList();
        assertThat(activeSessions).hasSize(1);
        return activeSessions.getFirst();
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = current_schema()
                          and table_name = ?
                          and column_name = ?
                        """,
                Integer.class,
                tableName,
                columnName);
        return count != null && count > 0;
    }

    private void seedUserEmployeeProjectionRecords(String tenantId) {
        jdbcTemplate.update("delete from iam_employee_account where tenant_id = ?", tenantId);
        jdbcTemplate.update("delete from iam_employee where tenant_id = ?", tenantId);
        jdbcTemplate.update("delete from iam_department where tenant_id = ?", tenantId);
        jdbcTemplate.update("delete from iam_organization where tenant_id = ?", tenantId);
        jdbcTemplate.update("delete from iam_user where tenant_id = ?", tenantId);
        String organizationId = projectionOrganizationId(tenantId);
        String departmentId = projectionDepartmentId(tenantId);
        insertOrganization(tenantId, organizationId, "PROJ-ORG", "Projection Organization");
        insertDepartment(tenantId, departmentId, organizationId, "PROJ-DEPT", "Projection Department");
        insertUser(tenantId, projectionUserId(tenantId, "alice"), projectionUsername(tenantId, "alice"));
        insertUser(tenantId, projectionUserId(tenantId, "bob"), projectionUsername(tenantId, "bob"));
        insertUser(tenantId, projectionUserId(tenantId, "charlie"), projectionUsername(tenantId, "charlie"));
        insertUser(tenantId, projectionUserId(tenantId, "dave"), projectionUsername(tenantId, "dave"));
        insertEmployee(tenantId, projectionEmployeeId(tenantId, "alice"), organizationId, departmentId,
                "E-PROJ-001", "Alice Employee", false);
        insertEmployee(tenantId, projectionEmployeeId(tenantId, "charlie"), organizationId, departmentId,
                "E-PROJ-003", "Charlie Employee", false);
        insertEmployee(tenantId, projectionEmployeeId(tenantId, "dave"), organizationId, departmentId,
                "E-PROJ-004", "Dave Employee", true);
        insertEmployeeAccount(tenantId, projectionEmployeeAccountId(tenantId, "alice"),
                projectionEmployeeId(tenantId, "alice"), projectionUserId(tenantId, "alice"), false);
        insertEmployeeAccount(tenantId, projectionEmployeeAccountId(tenantId, "charlie"),
                projectionEmployeeId(tenantId, "charlie"), projectionUserId(tenantId, "charlie"), true);
        insertEmployeeAccount(tenantId, projectionEmployeeAccountId(tenantId, "dave"),
                projectionEmployeeId(tenantId, "dave"), projectionUserId(tenantId, "dave"), false);
    }

    private void insertUser(String tenantId, String id, String username) {
        jdbcTemplate.update("""
                        insert into iam_user (
                            id, tenant_id, title, username, password_hash, password_status,
                            enabled, deleted, auth_user_id, auth_module_alias
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, tenantId, username, username, "test-password-hash", "ACTIVE",
                Boolean.TRUE, Boolean.FALSE, id, UserAccountService.MODULE_ALIAS);
    }

    private void insertOrganization(String tenantId, String id, String code, String title) {
        jdbcTemplate.update("""
                        insert into iam_organization (
                            id, tenant_id, title, code, enabled, deleted
                        ) values (?, ?, ?, ?, ?, ?)
                        """,
                id, tenantId, title, code, Boolean.TRUE, Boolean.FALSE);
    }

    private void insertDepartment(String tenantId, String id, String organizationId, String code, String title) {
        jdbcTemplate.update("""
                        insert into iam_department (
                            id, tenant_id, title, organization_id, code, enabled, deleted
                        ) values (?, ?, ?, ?, ?, ?, ?)
                        """,
                id, tenantId, title, organizationId, code, Boolean.TRUE, Boolean.FALSE);
    }

    private String projectionOrganizationId(String tenantId) {
        return projectionId("org", tenantId, "main");
    }

    private String projectionDepartmentId(String tenantId) {
        return projectionId("dept", tenantId, "main");
    }

    private String projectionUserId(String tenantId, String code) {
        return projectionId("user", tenantId, code);
    }

    private String projectionEmployeeId(String tenantId, String code) {
        return projectionId("emp", tenantId, code);
    }

    private String projectionEmployeeAccountId(String tenantId, String code) {
        return projectionId("bind", tenantId, code);
    }

    private String projectionUsername(String tenantId, String code) {
        return code + "_projection_" + projectionTenantSuffix(tenantId);
    }

    private String projectionId(String prefix, String tenantId, String code) {
        return "projection_" + prefix + "_" + projectionTenantSuffix(tenantId) + "_" + code;
    }

    private String projectionTenantSuffix(String tenantId) {
        return Integer.toUnsignedString(tenantId.hashCode(), 36);
    }

    private void insertEmployee(String tenantId,
                                String id,
                                String organizationId,
                                String departmentId,
                                String employeeNo,
                                String title,
                                boolean deleted) {
        jdbcTemplate.update("""
                        insert into iam_employee (
                            id, tenant_id, title, organization_id, department_id, employee_no,
                            enabled, deleted
                        ) values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, tenantId, title, organizationId, departmentId, employeeNo,
                Boolean.TRUE, deleted);
    }

    private void insertEmployeeAccount(String tenantId, String id, String employeeId, String userId, boolean deleted) {
        jdbcTemplate.update("""
                        insert into iam_employee_account (
                            id, tenant_id, employee_id, user_id, deleted
                        ) values (?, ?, ?, ?, ?)
                        """,
                id, tenantId, employeeId, userId, deleted);
    }
}
