package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.currency.Currency;
import net.ximatai.muyun.spring.platform.currency.CurrencyConversion;
import net.ximatai.muyun.spring.platform.currency.CurrencyConversionService;
import net.ximatai.muyun.spring.platform.currency.CurrencyService;
import net.ximatai.muyun.spring.platform.currency.ExchangeRate;
import net.ximatai.muyun.spring.platform.currency.ExchangeRateService;
import net.ximatai.muyun.spring.platform.currency.TenantCurrencySetting;
import net.ximatai.muyun.spring.platform.currency.TenantCurrencySettingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CurrencyWebControllerTest {
    @Test
    void shouldPublishTenantRequiredSingleSettingPageWithoutAnotherTenantNavigator() {
        TenantCurrencySettingWebController controller = new TenantCurrencySettingWebController();
        ReflectionTestUtils.setField(controller, "service", mock(TenantCurrencySettingService.class));
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(TenantCurrencySettingWebController.class, () -> controller);
            context.refresh();
            var definition = new StaticModuleDefinitionScanner(context).scan().getFirst();
            assertThat(definition.tenantRequired()).isTrue();
            assertThat(definition.actions()).extracting(action -> action.actionCode())
                    .contains("menu", "query", "view", "create", "update");
            var page = (FlatManagementPageDefinition) definition.uiDefinition().page();
            assertThat(page.navigator()).isNull();
            assertThat(page.detail().editor().fields()).extracting(field -> field.fieldRef().fieldName())
                    .containsExactly("baseCurrencyCode");
            var loader = getClass().getClassLoader();
            var languages = net.ximatai.muyun.spring.common.i18n.LanguageCatalog.load(loader, "zh-CN");
            var resources = net.ximatai.muyun.spring.common.i18n.TextResources.load(loader, languages);
            var descriptor = ModuleUiDescriptorCompiler.compile(definition);
            assertThat(descriptor.sourceTexts()).isNotEmpty();
            for (var source : descriptor.sourceTexts().values()) {
                var english = resources.resolve(source, java.util.Map.of(), "en");
                assertThat(english.missing()).as(source.sourceId()).isFalse();
                assertThat(english.text()).as(source.sourceId()).doesNotContainPattern("[\\p{IsHan}]");
            }
        }
    }

    @Test
    void shouldRejectTenantlessSettingQueries() {
        TenantCurrencySettingWebController controller = new TenantCurrencySettingWebController();
        ReflectionTestUtils.setField(controller, "service", mock(TenantCurrencySettingService.class));
        try (TenantContext.Scope ignored = TenantContext.system("currency setting test")) {
            assertThatThrownBy(() -> controller.query(new org.springframework.mock.web.MockHttpServletRequest(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires tenant context");
        }
    }

    @Test
    void shouldKeepTenantContextWhenListingCurrencyOptions() throws Exception {
        CurrencyService service = mock(CurrencyService.class);
        CurrencyWebController controller = new CurrencyWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        when(service.listVisibleCurrencies(true)).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            return List.of(currency("USD"));
        });

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(get("/platform.currency/options"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records[0].code").value("USD"));
        }
    }

    @Test
    void shouldKeepTenantContextWhenConvertingCurrency() throws Exception {
        ExchangeRateService service = mock(ExchangeRateService.class);
        CurrencyConversionService conversionService = mock(CurrencyConversionService.class);
        ExchangeRateWebController controller = new ExchangeRateWebController(conversionService);
        ReflectionTestUtils.setField(controller, "service", service);
        when(conversionService.convert(new BigDecimal("12.34"), "USD", "CNY", "SPOT", LocalDate.parse("2026-02-16")))
                .thenAnswer(invocation -> {
                    assertThat(TenantContext.currentTenantId()).contains("tenant-a");
                    return new CurrencyConversion("USD", "CNY", "SPOT", LocalDate.parse("2026-02-16"),
                            new BigDecimal("12.34"), new BigDecimal("7.2"), new BigDecimal("88.85"));
                });

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/platform.exchange_rate/convert")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount":12.34,"fromCurrencyCode":"USD","toCurrencyCode":"CNY",
                                    "rateTypeCode":"SPOT","rateDate":"2026-02-16"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.exchangeRate").value(7.2));
        }
    }

    @Test
    void shouldQueryTenantCurrencySettingWithinCurrentTenant() throws Exception {
        TenantCurrencySettingService service = mock(TenantCurrencySettingService.class);
        TenantCurrencySettingWebController controller = new TenantCurrencySettingWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        TenantCurrencySetting setting = setting("tenant-a", "CNY");
        when(service.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(setting), 1, PageRequest.of(1, 20)));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/platform.tenant_currency_setting/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records[0].tenantId").value("tenant-a"));
        }

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort[].class));
        assertClause(criteria.getValue(), "tenantId", "tenant-a");
    }

    @Test
    void shouldKeepTenantContextWhenCreatingTenantCurrencySetting() throws Exception {
        TenantCurrencySettingService service = mock(TenantCurrencySettingService.class);
        TenantCurrencySettingWebController controller = new TenantCurrencySettingWebController();
        ReflectionTestUtils.setField(controller, "service", service);
        when(service.insert(any(TenantCurrencySetting.class))).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            return "setting-1";
        });
        when(service.select("setting-1")).thenReturn(setting("tenant-a", "CNY"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/platform.tenant_currency_setting/insert")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"baseCurrencyCode\":\"CNY\",\"title\":\"Base\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tenantId").value("tenant-a"))
                    .andExpect(jsonPath("$.baseCurrencyCode").value("CNY"));
        }
    }

    private Currency currency(String code) {
        Currency currency = new Currency();
        currency.setCode(code);
        currency.setTitle(code);
        return currency;
    }

    private TenantCurrencySetting setting(String tenantId, String baseCurrencyCode) {
        TenantCurrencySetting setting = new TenantCurrencySetting();
        setting.setId("setting-1");
        setting.setTenantId(tenantId);
        setting.setBaseCurrencyCode(baseCurrencyCode);
        setting.setTitle("Base");
        return setting;
    }

    private void assertClause(Criteria criteria, String fieldName, Object expected) {
        boolean matched = criteria.getClauses().stream()
                .anyMatch(clause -> fieldName.equals(clause.getField())
                        && clause.getOperator() == CriteriaOperator.EQ
                        && clause.getValues().contains(expected));
        assertThat(matched).isTrue();
    }
}
