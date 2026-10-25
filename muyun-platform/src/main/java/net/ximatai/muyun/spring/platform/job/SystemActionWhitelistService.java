package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.query.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.JobSystemActionPolicy;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Set;

@Service
public class SystemActionWhitelistService extends AbstractAbilityService<SystemActionWhitelist>
        implements QueryAbility<SystemActionWhitelist>, JobSystemActionPolicy {
    public static final String MODULE_ALIAS = "platform.system_action_whitelist";
    private final SystemActionWhitelistDao rows;
    public SystemActionWhitelistService(SystemActionWhitelistDao rows) { super(MODULE_ALIAS, SystemActionWhitelist.class, rows); this.rows = rows; }
    public static void requirePlatformAdministrator() {
        var user = CurrentUserContext.currentUser().orElseThrow(() -> new PlatformAccessDeniedException(TextReference.code(
                "platform.rejection.scheduled-action.platform-administrator-required", "Platform administrator required"),
                ErrorScope.module(MODULE_ALIAS)));
        if (!user.system() || user.passwordChangeRequired()) throw new PlatformAccessDeniedException(TextReference.code(
                "platform.rejection.scheduled-action.platform-administrator-required", "Platform administrator required"),
                ErrorScope.module(MODULE_ALIAS));
    }
    @Override public Criteria activeCriteria(Criteria criteria) { requirePlatformAdministrator(); return criteria; }
    @Override public void beforeInsert(SystemActionWhitelist row) { validate(row); }
    @Override public void beforeUpdate(SystemActionWhitelist row) { validate(row); }
    @Override public void beforeDelete(String id) { requirePlatformAdministrator(); }
    private void validate(SystemActionWhitelist row) {
        requirePlatformAdministrator(); row.setTenantId(null);
        row.setModuleAlias(PlatformNameRules.requireModuleAlias(row.getModuleAlias()));
        row.setActionCode(PlatformNameRules.requireActionCode(row.getActionCode(), "actionCode"));
    }
    @Override public QueryDescriptor queryDescriptor() {
        var declared = QueryDescriptors.fromModel(MODULE_ALIAS, SystemActionWhitelist.class, List.of("id", "moduleAlias", "actionCode", "createdAt"), Sort.desc("createdAt"));
        var builder = QueryDescriptor.builder(MODULE_ALIAS).defaultSort(Sort.desc("createdAt"));
        declared.fields().forEach(field -> builder.field(Set.of("moduleAlias", "actionCode").contains(field.fieldName()) ? field.withQuickSearch() : field));
        return builder.build();
    }
    @Override public boolean allows(String module, String code) {
        try (var global = TenantContext.system("resolve system scheduled action whitelist")) {
            return !rows.query(Criteria.of().eq("moduleAlias", module).eq("actionCode", code).isNull("tenantId"), PageRequest.of(1, 1)).isEmpty();
        }
    }
}
