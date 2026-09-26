package net.ximatai.muyun.spring.platform.save;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** The receipt and the formal save participate in the same database transaction. */
@Service
public class RecordSaveReceiptService {
    private final BaseDao<RecordSaveReceipt, String> receipts;

    public RecordSaveReceiptService(BaseDao<RecordSaveReceipt, String> receipts) {
        this.receipts = Objects.requireNonNull(receipts);
    }

    @Transactional
    public <T extends EntityContract> T execute(String requestId, String moduleAlias, String action,
            String payloadDigest, Supplier<T> save, Function<String, T> authorizedRead) {
        if (!"create".equals(action) && !"update".equals(action))
            throw new IllegalArgumentException("Only standard record saves support receipts");
        String key = requestDigest(requestId);
        PlatformAbilityRuntime.lockMutationPartition("platform.record-save", key);
        RecordSaveReceipt existing = find(key);
        if (existing != null) {
            if (!Objects.equals(existing.getModuleAlias(), moduleAlias)
                    || !Objects.equals(existing.getActionCode(), action)
                    || !Objects.equals(existing.getPayloadDigest(), payloadDigest))
                throw new IllegalArgumentException("保存请求内容已变化，请重新审阅并确认");
            // Never replay stored business values after permissions have changed.
            return authorizedRead.apply(existing.getRecordId());
        }
        T saved = Objects.requireNonNull(save.get(), "save result");
        if (saved.getId() == null) throw new IllegalStateException("save result has no record identity");
        RecordSaveReceipt receipt = new RecordSaveReceipt();
        receipt.setId(key.substring(0, 32));
        receipt.setRequestDigest(key);
        receipt.setPayloadDigest(payloadDigest);
        receipt.setModuleAlias(moduleAlias);
        receipt.setActionCode(action);
        receipt.setRecordId(saved.getId());
        receipt.setRecordVersion(saved.getVersion());
        receipt.setTenantId(TenantContext.currentTenantId().orElse(null));
        EntityLifecycle.prepareInsert(receipt, Instant.now());
        receipts.insert(receipt);
        return saved;
    }

    public RecordSaveReceipt lookup(String requestId, String moduleAlias) {
        RecordSaveReceipt receipt = find(requestDigest(requestId));
        return receipt != null && Objects.equals(receipt.getModuleAlias(), moduleAlias) ? receipt : null;
    }

    private RecordSaveReceipt find(String digest) {
        RecordSaveReceipt receipt = receipts.findById(digest.substring(0, 32));
        if (receipt != null && !digest.equals(receipt.getRequestDigest()))
            throw new IllegalStateException("Save request identity collision");
        return receipt;
    }

    private String requestDigest(String requestId) {
        if (requestId == null || !requestId.matches("[a-zA-Z0-9-]{16,80}"))
            throw new IllegalArgumentException("Invalid save request identity");
        String user = CurrentUserContext.currentUser().orElseThrow(
                () -> new IllegalStateException("Confirmed saves require an authenticated user")).userId();
        String tenant = TenantContext.currentTenantId().orElse("");
        return digest(user.length() + ":" + user + tenant.length() + ":" + tenant + ":" + requestId);
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
