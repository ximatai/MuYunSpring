package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoad;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadFacade;
import net.ximatai.muyun.spring.ability.reference.ReferenceSummary;
import net.ximatai.muyun.spring.ability.reference.ReferenceHop;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.platform.reference.PlatformReferenceLoadResolver;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferenceReadProjectionPostProcessorTest {
    @AfterEach
    void tearDown() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void shouldBatchEnrichStaticRecordsFromADynamicReferenceTarget() {
        @SuppressWarnings("unchecked")
        ReferenceAbility<?> target = mock(ReferenceAbility.class);
        ReferenceTarget customer = ReferenceTarget.of("crm", "customer");
        when(target.projections(eq(List.of("customer-1", "customer-2")), eq(List.of("title", "level"))))
                .thenReturn(Map.of(
                        "customer-1", Map.of("title", "客户一", "level", "A"),
                        "customer-2", Map.of("title", "客户二", "level", "B")
                ));
        PlatformAbilityRuntime.configureReferenceTargetResolver(reference -> customer.equals(reference)
                ? java.util.Optional.of(target)
                : java.util.Optional.empty());

        List<Map<String, Object>> result = ReferenceReadProjectionPostProcessor.apply(StaticOrder.class, List.of(
                Map.of("id", "order-1", "customerId", "customer-1"),
                Map.of("id", "order-2", "customerId", "customer-2")
        ));

        assertThat(result).containsExactly(
                Map.of("id", "order-1", "customerId", "customer-1", "customerTitle", "客户一", "customerLevel", "A"),
                Map.of("id", "order-2", "customerId", "customer-2", "customerTitle", "客户二", "customerLevel", "B")
        );
        verify(target).projections(List.of("customer-1", "customer-2"), List.of("title", "level"));
    }

    @Test
    void shouldKeepDeclaredReferenceLoadEqualBetweenDomainFacadeAndListProjection() {
        DomainOrder domainOrder = new DomainOrder();
        domainOrder.setId("order-1");
        domainOrder.customerId = "customer-1";
        @SuppressWarnings("unchecked") CrudAbility<DomainOrder> orders = mock(CrudAbility.class);
        doReturn(DomainOrder.class).when(orders).modelClass();
        when(orders.getModuleAlias()).thenReturn("crm.order");
        @SuppressWarnings("unchecked") ReferenceAbility<CustomerRecord> customer = mock(ReferenceAbility.class);
        doReturn(CustomerRecord.class).when(customer).modelClass();
        when(customer.getModuleAlias()).thenReturn("crm.customer");
        when(customer.projections(eq(List.of("customer-1")), eq(List.of("title"))))
                .thenReturn(Map.of("customer-1", Map.of("title", "客户一")));
        ReferenceTarget customerTarget = ReferenceTarget.of("crm", "customer");
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> customerTarget.equals(target)
                ? java.util.Optional.of(customer) : java.util.Optional.empty());

        new ReferenceReadFacade(new PlatformReferenceLoadResolver(
                new StaticAbilityCatalog(List.of(orders, customer)))).enrich(orders, List.of(domainOrder));
        List<Map<String, Object>> listed = ReferenceReadProjectionPostProcessor.apply(DomainOrder.class,
                List.of(Map.of("id", "order-1", "customerId", "customer-1")));

        assertThat(domainOrder.customerTitle).isEqualTo("客户一");
        assertThat(listed).containsExactly(Map.of(
                "id", "order-1", "customerId", "customer-1", "customerTitle", "客户一"));
        verify(customer, times(2)).projections(List.of("customer-1"), List.of("title"));
    }

    @Test
    void shouldStripInternalReadFieldsEvenWhenNoReferenceProjectionIsRequested() {
        List<Map<String, Object>> result = ReferenceReadProjectionPostProcessor.apply(PlainRecord.class, List.of(
                Map.of("id", "record-1", "version", 2, "tenantId", "tenant-a", "title", "Visible")
        ), List.of("title"));

        assertThat(result).containsExactly(Map.of("id", "record-1", "version", 2, "title", "Visible"));
    }

    @Test
    void shouldStripInternalReadFieldsWhenTheModelIsUnavailable() {
        List<Map<String, Object>> result = ReferenceReadProjectionPostProcessor.apply(null, List.of(
                Map.of("id", "record-1", "version", 2, "tenantId", "tenant-a", "title", "Visible")
        ), List.of("title"));

        assertThat(result).containsExactly(Map.of("id", "record-1", "version", 2, "title", "Visible"));
    }

    @Test
    void shouldBatchEnrichStaticRecordsAcrossReferenceLoadHops() {
        @SuppressWarnings("unchecked") ReferenceAbility<?> middle = mock(ReferenceAbility.class);
        @SuppressWarnings("unchecked") ReferenceAbility<?> terminal = mock(ReferenceAbility.class);
        ReferenceTarget middleTarget = ReferenceTarget.of("demo", "middle");
        ReferenceTarget terminalTarget = ReferenceTarget.of("demo", "terminal");
        when(middle.projections(eq(List.of("middle-1", "middle-2")), eq(List.of("terminalId"))))
                .thenReturn(Map.of("middle-1", Map.of("terminalId", "terminal-1"),
                        "middle-2", Map.of("terminalId", "terminal-2")));
        when(terminal.projections(eq(List.of("terminal-1", "terminal-2")), eq(List.of("title"))))
                .thenReturn(Map.of("terminal-1", Map.of("title", "终点一"),
                        "terminal-2", Map.of("title", "终点二")));
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> middleTarget.equals(target)
                ? java.util.Optional.of(middle)
                : terminalTarget.equals(target) ? java.util.Optional.of(terminal) : java.util.Optional.empty());

        List<Map<String, Object>> result = ReferenceReadProjectionPostProcessor.apply(TwoHopOrder.class, List.of(
                Map.of("id", "order-1", "middleId", "middle-1"),
                Map.of("id", "order-2", "middleId", "middle-2")
        ), List.of("terminalTitle"));

        assertThat(result).containsExactly(
                Map.of("id", "order-1", "terminalTitle", "终点一"),
                Map.of("id", "order-2", "terminalTitle", "终点二"));
        verify(middle).projections(List.of("middle-1", "middle-2"), List.of("terminalId"));
        verify(terminal).projections(List.of("terminal-1", "terminal-2"), List.of("title"));
    }

    @Test
    void shouldProjectManyReferenceAsStructuredSummaries() {
        @SuppressWarnings("unchecked") ReferenceAbility<?> tag = mock(ReferenceAbility.class);
        ReferenceTarget tagTarget = ReferenceTarget.of("mr", "tag");
        when(tag.projections(eq(List.of("tag-1", "tag-2")), eq(List.of("title", "color"))))
                .thenReturn(Map.of("tag-1", Map.of("title", "纯电动", "color", "#22C55E"),
                        "tag-2", Map.of("title", "XE215EV", "color", "#2563EB")));
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> tagTarget.equals(target)
                ? java.util.Optional.of(tag) : java.util.Optional.empty());

        List<Map<String, Object>> result = ReferenceReadProjectionPostProcessor.apply(TaggableRecord.class,
                List.of(Map.of("id", "device-1", "tagIds", List.of("tag-1", "tag-2"))), List.of("tagSummaries"));

        assertThat(result).singleElement().extracting(record -> record.get("tagSummaries"))
                .isEqualTo(List.of(Map.of("id", "tag-1", "title", "纯电动", "color", "#22C55E"),
                        Map.of("id", "tag-2", "title", "XE215EV", "color", "#2563EB")));
        verify(tag).projections(List.of("tag-1", "tag-2"), List.of("title", "color"));
    }

    @Test
    void shouldProjectIdOnlyReferenceSummaryWithoutTargetLookup() {
        List<Map<String, Object>> result = ReferenceReadProjectionPostProcessor.apply(IdOnlyTaggableRecord.class,
                List.of(Map.of("id", "device-1", "tagIds", List.of("tag-1", "tag-2"))), List.of("tagSummaries"));

        assertThat(result).singleElement().extracting(record -> record.get("tagSummaries"))
                .isEqualTo(List.of(Map.of("id", "tag-1"), Map.of("id", "tag-2")));
    }

    private static final class StaticOrder {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "customer")
        private String customerId;

        @ReferenceLoad(source = "customerId", field = "title")
        private transient String customerTitle;

        @ReferenceLoad(source = "customerId", field = "level")
        private transient String customerLevel;
    }

    private static final class DomainOrder extends StandardEntity {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "customer")
        private String customerId;

        @ReferenceLoad(source = "customerId", field = "title")
        private transient String customerTitle;
    }

    private static final class CustomerRecord extends StandardTitledEntity {
    }

    private static final class PlainRecord {
        private String title;
    }

    private static final class TwoHopOrder {
        @ReferenceTo(moduleAlias = "demo", entityAlias = "middle")
        private String middleId;

        @ReferenceLoad(source = "middleId", hops = @ReferenceHop(target = TerminalService.class, via = "terminalId"))
        private transient String terminalTitle;
    }

    private static final class TaggableRecord {
        @ReferenceTo(moduleAlias = "mr", entityAlias = "tag", cardinality = net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.MANY)
        private java.util.Set<String> tagIds;

        @ReferenceSummary(source = "tagIds", fields = {"title", "color"})
        private transient List<Map<String, Object>> tagSummaries;
    }

    private static final class IdOnlyTaggableRecord {
        @ReferenceTo(moduleAlias = "mr", entityAlias = "tag",
                cardinality = net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.MANY)
        private java.util.Set<String> tagIds;

        @ReferenceSummary(source = "tagIds", fields = {"id"})
        private transient List<Map<String, Object>> tagSummaries;
    }

    public static final class TerminalService {
        public static final String MODULE_ALIAS = "demo.terminal";
    }
}
