package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.localization.ConfiguredTextSourceProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Service
public class PasswordPolicyConfiguredTextSourceProvider implements ConfiguredTextSourceProvider {
    private final ObjectProvider<PasswordPolicyRuleService> rules;

    public PasswordPolicyConfiguredTextSourceProvider(ObjectProvider<PasswordPolicyRuleService> rules) {
        this.rules = Objects.requireNonNull(rules);
    }

    @Override public List<TextReference> currentSources(String moduleAlias) {
        if (!TenantContext.isSystem()) throw new IllegalStateException("Configuration source maintenance requires system scope");
        if (!PasswordPolicyRuleService.MODULE_ALIAS.equals(moduleAlias)) return List.of();
        var service = rules.getIfAvailable();
        return service == null ? List.of() : service.list(Criteria.of(), new PageRequest(0, Integer.MAX_VALUE)).stream()
                .flatMap(rule -> Stream.of(PasswordPolicyTextSources.title(rule), PasswordPolicyTextSources.message(rule)))
                .toList();
    }
}
