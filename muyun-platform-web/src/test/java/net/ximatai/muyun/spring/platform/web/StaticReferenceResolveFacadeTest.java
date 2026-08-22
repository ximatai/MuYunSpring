package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.web.WebReferenceResolveMode;
import net.ximatai.muyun.spring.web.WebReferenceResolveRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaticReferenceResolveFacadeTest {
    @Test
    void shouldResolveStaticCandidatesAndTranslationsThroughReferenceAbility() {
        @SuppressWarnings("unchecked") CrudAbility<Order> source = mock(CrudAbility.class);
        doReturn(Order.class).when(source).modelClass();
        when(source.getModuleAlias()).thenReturn("sales.order");
        @SuppressWarnings("unchecked") ReferenceAbility<Customer> target = mock(ReferenceAbility.class);
        doReturn(Customer.class).when(target).modelClass();
        when(target.getModuleAlias()).thenReturn("crm.customer");
        when(target.referenceOptions(any(), any(PageRequest.class)))
                .thenReturn(PageResult.of(List.of(new ReferenceOption("customer-1", "星云科技")), 1,
                        PageRequest.of(1, 20)));
        when(target.title("customer-1")).thenReturn("星云科技");

        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .modelClass(Order.class).build();
        StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                new StaticModuleDefinitionCatalog(List.of(definition)), new StaticAbilityCatalog(List.of(source, target)));

        var query = facade.resolve("sales.order", "customerId", new WebReferenceResolveRequest(
                WebReferenceResolveMode.QUERY, null, "星云", List.of(), List.of(), null,
                new net.ximatai.muyun.spring.web.WebPageRequest(1, 20), true, null, null, null, null, null));
        var translation = facade.resolve("sales.order", "customerId", new WebReferenceResolveRequest(
                WebReferenceResolveMode.TRANSLATE, null, null, List.of("customer-1"), List.of(), null,
                null, true, null, null, null, null, null));

        assertThat(query.options()).extracting(item -> item.title()).containsExactly("星云科技");
        assertThat(translation.results()).singleElement().extracting(result -> result.item().title())
                .isEqualTo("星云科技");
        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        ArgumentCaptor<PageRequest> page = ArgumentCaptor.forClass(PageRequest.class);
        verify(target).referenceOptions(criteria.capture(), page.capture());
        assertThat(criteria.getValue().getClauses()).singleElement().satisfies(clause -> {
            assertThat(clause.getField()).isEqualTo("title");
            assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.LIKE);
            assertThat(clause.getValues()).containsExactly("星云");
        });
        assertThat(page.getValue().getOffset()).isZero();
        assertThat(page.getValue().getLimit()).isEqualTo(20);
        verify(target).title("customer-1");
    }

    private static final class Order extends StandardEntity {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "customer")
        private String customerId;
    }

    private static final class Customer extends StandardTitledEntity {
    }
}
