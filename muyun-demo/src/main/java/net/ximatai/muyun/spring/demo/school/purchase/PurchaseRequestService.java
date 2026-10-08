package net.ximatai.muyun.spring.demo.school.purchase;
import net.ximatai.muyun.spring.ability.ApprovalAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.StandardBusinessService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
@Service @Profile("school-demo")
public class PurchaseRequestService extends StandardBusinessService<PurchaseRequest>
        implements ApprovalAbility<PurchaseRequest>, SoftDeleteAbility<PurchaseRequest> {
    public static final String MODULE_ALIAS = "education.purchase_request";
    public PurchaseRequestService(PurchaseRequestDao dao) { super(MODULE_ALIAS, PurchaseRequest.class, dao); }
}
