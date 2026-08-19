package net.ximatai.muyun.spring.platform.web.endpoint;

import net.ximatai.muyun.spring.web.endpoint.*;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.DisablePlatformOperations;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformStaticActionContribution;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebProjection;
import net.ximatai.muyun.spring.web.RecordWebProjectionPolicy;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.web.ScopedWeb;
import net.ximatai.muyun.spring.common.model.capability.EnabledCapable;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.mock.web.MockServletContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaticAbilityWebEndpointRegistrarTest {
    @Test
    void shouldGrowAndShrinkPhysicalMappingsFromServiceAbilityWithoutChangingController() throws Exception {
        Set<String> enabledMappings = mappings(mock(EnableAbility.class));
        Set<String> sortMappings = mappings(mock(SortAbility.class));
        Set<String> plainMappings = mappings(mock(CrudAbility.class));

        assertThat(enabledMappings).containsExactlyInAnyOrder(
                "/demo.resource/enable/{id}",
                "/demo.resource/disable/{id}"
        );
        assertThat(sortMappings).containsExactly("/demo.resource/sort/{id}");
        assertThat(plainMappings).isEmpty();
    }

    @Test
    void shouldRetainTheMappingActuallyAcceptedBySpringMvc() throws Exception {
        try (Fixture fixture = fixture(mock(EnableAbility.class))) {
            fixture.registrar().afterSingletonsInstantiated();

            assertThat(fixture.catalog().endpoints())
                    .extracting(endpoint -> endpoint.definition().endpointId())
                    .containsExactly("demo.resource.enable.enable", "demo.resource.enable.disable");
            assertThat(fixture.catalog().endpoints())
                    .allSatisfy(endpoint -> {
                        assertThat(endpoint.definition().source())
                                .isEqualTo(ResolvedWebEndpoint.Source.STATIC_ABILITY);
                        assertThat(endpoint.mapping().getPatternValues())
                                .contains(endpoint.definition().path());
                        assertThat(endpoint.mapping().getMethodsCondition().getMethods())
                                .containsExactly(endpoint.definition().method());
                        assertThat(org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                                endpoint.handler().getClass(), ResponseBody.class)).isNotNull();
                    });
            assertThat(fixture.catalog().endpoints())
                    .extracting(RegisteredWebEndpoint::handler)
                    .containsOnly(fixture.catalog().endpoints().getFirst().handler());
        }
    }

    @Test
    void shouldRemoveOnlyTheOperationsDisabledByTheConcreteService() throws Exception {
        assertThat(mappings(new EnableOnlyDisabledService())).containsExactly(
                "/demo.resource/disable/{id}"
        );
    }

    @Test
    void shouldRemoveDisabledDefaultCrudMappingsFromSpringMvc() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(DisabledCrudController.class,
                    () -> new DisabledCrudController(new ReadOnlyOperationService()));
            context.refresh();
            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();

            new StaticAbilityWebEndpointRegistrar(
                    context, mapping, new RegisteredWebEndpointCatalog(),
                    context.getBeanProvider(net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade.class)
            ).afterSingletonsInstantiated();

            Set<String> paths = mapping.getHandlerMethods().keySet().stream()
                    .flatMap(info -> info.getPatternValues().stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            assertThat(paths).doesNotContain(
                    "/demo.read_only/insert",
                    "/demo.read_only/update/{id}",
                    "/demo.read_only/delete/{id}");
            assertThat(paths).contains("/demo.read_only/query", "/demo.read_only/view/{id}");
        }
    }

    @Test
    void shouldAllowExplicitControllerReplacementOnlyAfterStandardOperationIsDisabled() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ExplicitReplacementController.class,
                    () -> new ExplicitReplacementController(new EnableOnlyDisabledService()));
            context.refresh();
            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();
            RegisteredWebEndpointCatalog catalog = new RegisteredWebEndpointCatalog();

            new StaticAbilityWebEndpointRegistrar(
                    context, mapping, catalog,
                    context.getBeanProvider(net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade.class)
            ).afterSingletonsInstantiated();

            assertThat(catalog.endpoints()).extracting(endpoint -> endpoint.definition().path())
                    .containsExactlyInAnyOrder(
                            "/demo.resource/activate/{id}",
                            "/demo.resource/disable/{id}"
                    );
            assertThat(catalog.endpoints())
                    .filteredOn(endpoint -> endpoint.definition().path().contains("activate"))
                    .singleElement()
                    .satisfies(endpoint -> assertThat(endpoint.definition().source())
                            .isEqualTo(ResolvedWebEndpoint.Source.STATIC_EXPLICIT));
        }
    }

    @Test
    void shouldRejectExplicitControllerOverrideWhileStandardOperationRemainsEnabled() throws Exception {
        EnableAbility<DemoRecord> service = mock(EnableAbility.class);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ConflictingExplicitController.class,
                    () -> new ConflictingExplicitController(service));
            context.refresh();
            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();

            StaticAbilityWebEndpointRegistrar registrar = new StaticAbilityWebEndpointRegistrar(
                    context, mapping, new RegisteredWebEndpointCatalog(),
                    context.getBeanProvider(net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade.class)
            );

            assertThatThrownBy(registrar::afterSingletonsInstantiated)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot replace enabled standard ability endpoint")
                    .hasMessageContaining("disable enable");
        }
    }

    @Test
    void shouldCompileTreeAndItsIntrinsicSortEndpointAsOneAbilitySurface() throws Exception {
        assertThat(mappings(mock(TreeAbility.class))).containsExactlyInAnyOrder(
                "/demo.resource/tree",
                "/demo.resource/tree/query",
                "/demo.resource/tree/{id}",
                "/demo.resource/sort/{id}"
        );
    }

    @Test
    void shouldCompileContributedResourceWithPrefixedActionPolicy() throws Exception {
        EnableAbility<DemoRecord> service = mock(EnableAbility.class);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ContributionController.class, () -> new ContributionController(service));
            context.refresh();
            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();
            RegisteredWebEndpointCatalog catalog = new RegisteredWebEndpointCatalog();

            new StaticAbilityWebEndpointRegistrar(
                    context, mapping, catalog,
                    context.getBeanProvider(net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade.class)
            ).afterSingletonsInstantiated();

            assertThat(catalog.endpoints())
                    .extracting(endpoint -> endpoint.definition().path())
                    .containsExactlyInAnyOrder(
                            "/demo.parent/{parentId}/children/enable/{id}",
                            "/demo.parent/{parentId}/children/disable/{id}");
            assertThat(catalog.endpoints())
                    .extracting(endpoint -> endpoint.definition().executionPolicy().actionCode())
                    .containsExactlyInAnyOrder("child_enable", "child_disable");
        }
    }

    @Test
    void shouldCompileAdditionalWebProjectionAndApplyProjectionLocalDisable() throws Exception {
        EnableAbility<DemoRecord> service = mock(EnableAbility.class);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(AdditionalProjectionController.class,
                    () -> new AdditionalProjectionController(service));
            context.refresh();
            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();
            RegisteredWebEndpointCatalog catalog = new RegisteredWebEndpointCatalog();

            new StaticAbilityWebEndpointRegistrar(
                    context, mapping, catalog,
                    context.getBeanProvider(net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade.class)
            ).afterSingletonsInstantiated();

            assertThat(catalog.endpoints())
                    .extracting(endpoint -> endpoint.definition().path())
                    .containsExactly("/demo/shared/enable/{id}");
            assertThat(catalog.endpoints())
                    .extracting(endpoint -> endpoint.definition().endpointId())
                    .allSatisfy(endpointId -> assertThat(endpointId)
                            .startsWith("demo.resource.enable.enable.projection."));
        }
    }

    @Test
    void shouldRejectUnanchoredStandardWebProjection() {
        EnableAbility<DemoRecord> service = mock(EnableAbility.class);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(UnanchoredProjectionController.class,
                    () -> new UnanchoredProjectionController(service));
            context.refresh();
            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();

            StaticAbilityWebEndpointRegistrar registrar = new StaticAbilityWebEndpointRegistrar(
                    context, mapping, new RegisteredWebEndpointCatalog(),
                    context.getBeanProvider(net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade.class)
            );

            assertThatThrownBy(registrar::afterSingletonsInstantiated)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("standard Web projection requires")
                    .hasMessageContaining(UnanchoredProjectionController.class.getName());
        }
    }

    @Test
    void shouldExposeIrreversiblePurgeOnlyWhenServicePolicyEnablesIt() throws Exception {
        RecycleBinAbility<?> retainedOnly = mock(RecycleBinAbility.class);
        RecycleBinAbility<?> purgeEnabled = mock(RecycleBinAbility.class);
        when(purgeEnabled.isRecycleBinPurgeEnabled()).thenReturn(true);

        assertThat(mappings(retainedOnly)).doesNotContain(
                "/demo.resource/recycle-bin/{sourceDeleteOperationId}/purge");
        assertThat(mappings(purgeEnabled)).contains(
                "/demo.resource/recycle-bin/{sourceDeleteOperationId}/purge");
    }

    @Test
    void shouldExposeRetainedRecordDetailAlongsideRecycleBinQuery() throws Exception {
        RecycleBinAbility<?> service = mock(RecycleBinAbility.class);

        assertThat(mappings(service)).contains(
                "/demo.resource/recycle-bin/query",
                "/demo.resource/recycle-bin/view/{id}");
    }

    @Test
    void shouldServeGeneratedEndpointThroughRealSpringMvcHandlerPipeline() throws Exception {
        EnableAbility<?> service = mock(EnableAbility.class);
        when(service.enable("record-1", 3)).thenReturn(1);
        when(service.disable("record-1", 4)).thenReturn(2);
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(MvcConfiguration.class);
            context.refresh();
            RegisteredWebEndpointCatalog catalog = new RegisteredWebEndpointCatalog();
            context.getBeanFactory().registerSingleton("demoController", new DemoController(service));
            new StaticAbilityWebEndpointRegistrar(
                    context,
                    context.getBean(RequestMappingHandlerMapping.class),
                    catalog,
                    context.getBeanProvider(net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade.class)
            ).afterSingletonsInstantiated();

            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc
                    .perform(post("/demo.resource/enable/record-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":3}"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("1"));
            mvc.perform(post("/demo.resource/disable/record-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":4}"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("2"));
        }
    }

    private Set<String> mappings(Object service) throws Exception {
        try (Fixture fixture = fixture(service)) {
            fixture.registrar().afterSingletonsInstantiated();
            return fixture.catalog().endpoints().stream()
                    .map(endpoint -> endpoint.definition().path())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private Fixture fixture(Object service) throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(DemoController.class, () -> new DemoController(service));
        if (service instanceof RecycleBinAbility<?>) {
            context.registerBean(net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade.class,
                    () -> mock(net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade.class));
        }
        context.refresh();
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
        RegisteredWebEndpointCatalog catalog = new RegisteredWebEndpointCatalog();
        StaticAbilityWebEndpointRegistrar registrar = new StaticAbilityWebEndpointRegistrar(
                context,
                mapping,
                catalog,
                context.getBeanProvider(net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade.class)
        );
        return new Fixture(context, registrar, catalog);
    }

    private record Fixture(GenericApplicationContext context,
                           StaticAbilityWebEndpointRegistrar registrar,
                           RegisteredWebEndpointCatalog catalog) implements AutoCloseable {
        @Override
        public void close() {
            context.close();
        }
    }

    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class, alias = "demo.resource", title = "Demo")
    @RequestMapping("/demo.resource")
    static class DemoController implements ScopedWeb<Object> {
        private final Object service;

        DemoController(Object service) {
            this.service = service;
        }

        @Override
        public Object service() {
            return service;
        }

        @Override
        public <T> T webScope(java.util.function.Supplier<T> action) {
            return action.get();
        }
    }

    @PlatformStaticActionContribution(
            targetModule = "demo.parent",
            resource = "child",
            resourceTitle = "Child"
    )
    @RequestMapping("/demo.parent/{parentId}/children")
    static final class ContributionController implements ScopedWeb<EnableAbility<DemoRecord>> {
        private final EnableAbility<DemoRecord> service;

        ContributionController(EnableAbility<DemoRecord> service) {
            this.service = service;
        }

        @Override
        public EnableAbility<DemoRecord> service() {
            return service;
        }

        @Override
        public <T> T webScope(java.util.function.Supplier<T> action) {
            return action.get();
        }
    }

    @PlatformStaticWebProjection(
            module = "demo.resource",
            disabledOperations = PlatformAction.DISABLE
    )
    @RequestMapping("/demo/shared")
    static final class AdditionalProjectionController extends UnanchoredProjectionController {
        AdditionalProjectionController(EnableAbility<DemoRecord> service) {
            super(service);
        }
    }

    @RequestMapping("/demo/unanchored")
    static class UnanchoredProjectionController
            implements ScopedWeb<EnableAbility<DemoRecord>>, RecordWebProjectionPolicy {
        private final EnableAbility<DemoRecord> service;

        UnanchoredProjectionController(EnableAbility<DemoRecord> service) {
            this.service = service;
        }

        @Override
        public EnableAbility<DemoRecord> service() {
            return service;
        }

        @Override
        public void requireRecord(jakarta.servlet.http.HttpServletRequest request,
                                  PlatformAction action,
                                  String id) {
        }

        @Override
        public <T> T webScope(java.util.function.Supplier<T> action) {
            return action.get();
        }
    }

    @Configuration
    @EnableWebMvc
    static class MvcConfiguration {
    }

    static final class DemoRecord extends StandardEntity implements EnabledCapable {
        private Boolean enabled;

        @Override
        public Boolean getEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class, alias = "demo.resource", title = "Demo")
    @RequestMapping("/demo.resource")
    static final class ExplicitReplacementController extends DemoController {
        ExplicitReplacementController(Object service) {
            super(service);
        }

        @PostMapping("/activate/{id}")
        @ActionEndpoint(PlatformAction.ENABLE)
        int activate(@PathVariable String id) {
            return 1;
        }
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class, alias = "demo.resource", title = "Demo")
    @RequestMapping("/demo.resource")
    static final class ConflictingExplicitController extends DemoController {
        ConflictingExplicitController(Object service) {
            super(service);
        }

        @PostMapping("/enable/{id}")
        @ActionEndpoint(PlatformAction.ENABLE)
        int enable(@PathVariable String id) {
            return 1;
        }
    }

    @DisablePlatformOperations(PlatformAction.ENABLE)
    static final class EnableOnlyDisabledService implements EnableAbility<DemoRecord> {
        @Override
        public BaseDao<DemoRecord, String> getDao() {
            return null;
        }

        @Override
        public String getModuleAlias() {
            return "demo.resource";
        }
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.web.StaticTestApplications.DemoApplication.class, alias = "demo.read_only", title = "Read only")
    @RequestMapping("/demo.read_only")
    static final class DisabledCrudController implements CrudWeb<DemoRecord, CrudAbility<DemoRecord>> {
        private final CrudAbility<DemoRecord> service;

        DisabledCrudController(CrudAbility<DemoRecord> service) {
            this.service = service;
        }

        @Override
        public CrudAbility<DemoRecord> service() {
            return service;
        }

        @Override
        public <T> T webScope(java.util.function.Supplier<T> action) {
            return action.get();
        }
    }

    @DisablePlatformOperations({PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE})
    static final class ReadOnlyOperationService implements CrudAbility<DemoRecord> {
        @Override
        public BaseDao<DemoRecord, String> getDao() {
            return null;
        }

        @Override
        public String getModuleAlias() {
            return "demo.read_only";
        }
    }
}
