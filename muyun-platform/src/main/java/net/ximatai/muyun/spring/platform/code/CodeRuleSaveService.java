package net.ximatai.muyun.spring.platform.code;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Confirmed configuration writes reuse the normal rule-tree and field-binding transaction. */
@Service
public class CodeRuleSaveService {
    private final CodeRuleService rules;
    private final CodeRuleSaveReceiptDao receipts;
    private final CodeConfigurationService configuration;
    private final ObjectMapper mapper;
    public CodeRuleSaveService(CodeRuleService rules, CodeRuleSaveReceiptDao receipts,
            CodeConfigurationService configuration, ObjectMapper mapper) {
        this.rules = Objects.requireNonNull(rules);
        this.receipts = Objects.requireNonNull(receipts);
        this.configuration = Objects.requireNonNull(configuration);
        this.mapper = Objects.requireNonNull(mapper);
    }
    @Transactional
    public CodeRule save(String requestId, CodeRule command) {
        String target = target(command.getModuleAlias(), command.getEntityAlias(), command.getFieldName());
        String key = key(requestId);
        PlatformAbilityRuntime.lockMutationPartition("code.rule-save", key);
        String payload;
        CodeRule copy;
        try { payload = digest(mapper.writeValueAsString(command)); copy = mapper.readValue(mapper.writeValueAsString(command), CodeRule.class); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid code rule command", failure); }
        var existing = find(key);
        if (existing != null) {
            if (!target.equals(existing.getTargetKey()) || Boolean.TRUE.equals(existing.getCancelled()) || !payload.equals(existing.getPayloadDigest()))
                throw new IllegalArgumentException("原编号保存请求已终止或内容已变化");
            return rules.viewRuleTree(existing.getRuleId());
        }
        var saved = rules.saveRuleTree(copy);
        var receipt = receipt(key, target);
        receipt.setPayloadDigest(payload);
        receipt.setRuleId(saved.getId());
        receipt.setSavedVersion(saved.getVersion());
        receipt.setCancelled(false);
        receipts.insert(receipt);
        return saved;
    }
    public Result lookup(String requestId, String moduleAlias, String entityAlias, String fieldName) {
        String target = target(moduleAlias, entityAlias, fieldName);
        return result(find(key(requestId)), target);
    }
    @Transactional
    public Result abandon(String requestId, String moduleAlias, String entityAlias, String fieldName) {
        String target = target(moduleAlias, entityAlias, fieldName);
        String key = key(requestId);
        PlatformAbilityRuntime.lockMutationPartition("code.rule-save", key);
        var existing = find(key);
        if (existing != null) {
            if (!target.equals(existing.getTargetKey())) throw new IllegalArgumentException("保存请求归属不一致");
            return result(existing, target);
        }
        var receipt = receipt(key, target);
        receipt.setPayloadDigest(""); receipt.setCancelled(true); receipts.insert(receipt);
        return result(receipt, target);
    }
    private String target(String module, String entity, String field) {
        // Resolve current, authorized static/dynamic target facts even when no receipt exists.
        boolean available = configuration.targets(module).stream().anyMatch(target -> entity.equals(target.entityAlias())
                && target.fields().stream().anyMatch(candidate -> field.equals(candidate.fieldName())));
        if (!available) throw new IllegalArgumentException("编号目标不可用");
        return module + "/" + entity + "/" + field;
    }
    private Result result(CodeRuleSaveReceipt receipt, String target) {
        if (receipt == null || !target.equals(receipt.getTargetKey())) return new Result(false, false, null, null);
        boolean cancelled = Boolean.TRUE.equals(receipt.getCancelled());
        if (!cancelled && rules.viewRuleTree(receipt.getRuleId()) == null) throw new IllegalArgumentException("原编号规则已不可读取");
        return new Result(!cancelled, cancelled, receipt.getRuleId(), receipt.getSavedVersion());
    }
    public record Result(boolean committed, boolean cancelled, String ruleId, Integer savedVersion) {}
    private CodeRuleSaveReceipt receipt(String key, String target) {
        var receipt = new CodeRuleSaveReceipt(); receipt.setId(key.substring(0,32)); receipt.setRequestDigest(key);
        receipt.setTargetKey(target); receipt.setTenantId(TenantContext.currentTenantId().orElseThrow(() -> new IllegalArgumentException("请选择规则所属租户")));
        EntityLifecycle.prepareInsert(receipt, Instant.now()); return receipt;
    }
    private CodeRuleSaveReceipt find(String key) {
        var receipt = receipts.findById(key.substring(0,32));
        if (receipt != null && !key.equals(receipt.getRequestDigest())) throw new IllegalStateException("Code save identity collision");
        return receipt;
    }
    private String key(String request) {
        if (request == null || !request.matches("[a-zA-Z0-9-]{16,80}")) throw new IllegalArgumentException("Invalid save request identity");
        String user = CurrentUserContext.currentUser().orElseThrow().userId();
        String tenant = TenantContext.currentTenantId().orElseThrow(() -> new IllegalArgumentException("请选择规则所属租户"));
        return digest(user.length() + ":" + user + tenant.length() + ":" + tenant + ":" + request);
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
