package net.ximatai.muyun.spring.demo.school.receipt;

import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.code.CodeAbility;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.platform.code.CodeRuntimeCommand;
import net.ximatai.muyun.spring.platform.code.CodeRuntimeFacade;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@Service @Profile("school-demo")
public class ReceiptService extends StandardBusinessService<Receipt> implements CodeAbility<Receipt> {
    public static final String MODULE_ALIAS = "education.receipt";
    private final CodeRuntimeFacade codes;
    public ReceiptService(ReceiptDao dao, CodeRuntimeFacade codes) {
        super(MODULE_ALIAS, Receipt.class, dao); this.codes = codes;
    }

    /** The endpoint owns action/data authorization; the service owns tenant, version and transaction consistency. */
    @Transactional
    public Receipt confirm(String id, Integer expectedVersion) {
        var receipt = select(id);
        if (receipt == null) throw PlatformErrors.notFound("Receipt not found", net.ximatai.muyun.spring.common.exception.ErrorScope.empty());
        if (expectedVersion == null || !expectedVersion.equals(receipt.getVersion()))
            throw new OptimisticLockException("record version conflict: " + id);
        if (receipt.getConfirmationNo() != null) return receipt;
        var issued = codes.issueAndBind(CodeRuntimeCommand.builder(MODULE_ALIAS, codeEntityAlias(), "confirmationNo")
                .sourceRecordId(id).organizationId(codeOrganizationId(receipt)).context(Map.of("subject", receipt.getSubject())).build());
        receipt.setConfirmationNo(issued.value());
        update(receipt);
        return receipt;
    }
}
