package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.reference.ReferencerAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.List;

final class DemoInvoiceService extends AbstractAbilityService<DemoInvoice> implements
        SoftDeleteAbility<DemoInvoice>,
        ChildrenAbility<DemoInvoice>,
        ReferencerAbility<DemoInvoice>,
        ReferenceAbility<DemoInvoice>,
        CacheAbility<DemoInvoice> {
    private final DemoInvoiceLineService lineService = new DemoInvoiceLineService();
    private final DemoInvoiceNoteService noteService = new DemoInvoiceNoteService();
    private final DemoCustomerService customerService;
    private int businessHookCount;
    private String lastAfterSelectCustomerTitle;

    DemoInvoiceService() {
        this(new DemoCustomerService());
        DemoCustomer customer = new DemoCustomer("Customer One", "ACTIVE");
        customer.setId("customer-1");
        customerService.insert(customer);
    }

    DemoInvoiceService(DemoCustomerService customerService) {
        super("demo.demoInvoice", DemoInvoice.class, new InMemoryBaseDao<>());
        this.customerService = customerService;
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> {
            if (customerService.referenceTarget().equals(target)) {
                return java.util.Optional.of(customerService);
            }
            if (referenceTarget().equals(target)) {
                return java.util.Optional.of(this);
            }
            return java.util.Optional.empty();
        });
    }

    @Override
    public List<ChildRelation<? extends EntityContract, DemoInvoice>> childRelations() {
        return List.of(
                childRelation("lines", lineService),
                childRelation("notes", noteService)
        );
    }

    @Override
    public boolean usesAutomaticChildRelations() {
        return false;
    }

    DemoInvoiceLineService lineService() {
        return lineService;
    }

    DemoInvoiceNoteService noteService() {
        return noteService;
    }

    DemoCustomerService customerService() {
        return customerService;
    }

    @Override
    public void afterInsert(String id, DemoInvoice entity) {
        businessHookCount++;
    }

    @Override
    public void afterUpdate(DemoInvoice entity, int updated) {
        businessHookCount++;
    }

    @Override
    public void afterDelete(String id, DemoInvoice entity, int deleted) {
        businessHookCount++;
    }

    @Override
    public void afterSelect(DemoInvoice entity) {
        lastAfterSelectCustomerTitle = entity.getCustomerTitle();
        businessHookCount++;
    }

    int businessHookCount() {
        return businessHookCount;
    }

    String lastAfterSelectCustomerTitle() {
        return lastAfterSelectCustomerTitle;
    }
}
