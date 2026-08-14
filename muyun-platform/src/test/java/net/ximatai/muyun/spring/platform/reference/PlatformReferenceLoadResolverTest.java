package net.ximatai.muyun.spring.platform.reference;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoad;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadFacade;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformReferenceLoadResolverTest {
    @Test
    void shouldBatchPopulateDeclaredReferenceLoadFactsThroughDomainReadFacade() {
        OrderRecord first = order("order-1", "customer-1");
        OrderRecord second = order("order-2", "customer-2");
        CustomerAbility customers = new CustomerAbility();
        ModelAbility orders = new ModelAbility(OrderRecord.class, "test.order");
        ReferenceReadFacade reads = new ReferenceReadFacade(
                new PlatformReferenceLoadResolver(new StaticAbilityCatalog(List.of(orders, customers))));

        reads.enrich(orders, List.of(first, second));

        assertThat(customers.requests).containsExactly(List.of("customer-1", "customer-2"));
        assertThat(first.customerTitle).isEqualTo("客户一");
        assertThat(second.customerTitle).isEqualTo("客户二");
    }

    private static OrderRecord order(String id, String customerId) {
        OrderRecord record = new OrderRecord();
        record.setId(id);
        record.customerId = customerId;
        return record;
    }

    private static final class OrderRecord extends StandardEntity {
        @ReferenceTo(target = CustomerService.class)
        private String customerId;

        @ReferenceLoad(source = "customerId", field = "title")
        private transient String customerTitle;
    }

    private static final class CustomerRecord extends StandardTitledEntity {
    }

    public static final class CustomerService {
        public static final String MODULE_ALIAS = "test.customer";
    }

    private static final class ModelAbility implements CrudAbility<StandardEntity> {
        private final Class<?> modelClass;
        private final String moduleAlias;

        private ModelAbility(Class<?> modelClass, String moduleAlias) {
            this.modelClass = modelClass;
            this.moduleAlias = moduleAlias;
        }

        @Override
        public Class<?> modelClass() {
            return modelClass;
        }

        @Override
        public BaseDao<StandardEntity, String> getDao() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModuleAlias() {
            return moduleAlias;
        }
    }

    private static final class CustomerAbility implements ReferenceAbility<CustomerRecord> {
        private final List<List<String>> requests = new ArrayList<>();

        @Override
        public Class<?> modelClass() {
            return CustomerRecord.class;
        }

        @Override
        public BaseDao<CustomerRecord, String> getDao() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModuleAlias() {
            return CustomerService.MODULE_ALIAS;
        }

        @Override
        public Map<String, Map<String, Object>> projections(Collection<String> ids, Collection<String> fields) {
            requests.add(List.copyOf(ids));
            assertThat(fields).containsExactly("title");
            return Map.of(
                    "customer-1", Map.of("title", "客户一"),
                    "customer-2", Map.of("title", "客户二"));
        }
    }
}
