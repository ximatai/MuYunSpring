package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.EntitySaveLifecycleListener;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.metadata.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Guard the binding fact in the standard save chain, including ordinary metadata governance writes. */
@Component
public final class CodeBindingConfigurationGuard implements EntitySaveLifecycleListener {
    private final CodeRuleService rules;
    private final ModuleMetadataRelationService relations;
    private final MetadataService metadata;
    public CodeBindingConfigurationGuard(@Lazy CodeRuleService rules, ModuleMetadataRelationService relations, MetadataService metadata) {
        this.rules = rules; this.relations = relations; this.metadata = metadata;
    }
    @Override public <T extends EntityContract> void prepareValues(CrudAbility<T> owner, T previous, T incoming) {
        if (!(incoming instanceof ModuleMetadataField field) || !(previous instanceof ModuleMetadataField before)) return;
        if ((before.getBusinessCodeRole() != null || field.getBusinessCodeRole() != null)
                && (!Objects.equals(before.getRelationId(), field.getRelationId())
                || !Objects.equals(before.getMetadataFieldId(), field.getMetadataFieldId())))
            throw CodeConfigurationService.rejection("binding-identity", "A business code binding cannot be moved to another target field");
        if (before.getBusinessCodeRole() == field.getBusinessCodeRole()
                && before.getBusinessCodeTrigger() == field.getBusinessCodeTrigger()) return;
        var relation = relations.select(field.getRelationId());
        var schema = metadata.select(relation.getMetadataId());
        CodeConfigurationLocks.lock(relation.getModuleAlias(), schema.getAlias());
        try (var scope = field.getTenantId() == null ? TenantContext.system("validate shared business code field references") : TenantContext.use(field.getTenantId())) {
            boolean active = rules.selectRuleTreesByBusinessObject(relation.getModuleAlias(), schema.getAlias()).stream()
                    .anyMatch(rule -> Boolean.TRUE.equals(rule.getEnabled()) && (Objects.equals(rule.getModuleMetadataFieldId(), field.getId())
                            || Objects.equals(rule.getMetadataFieldId(), field.getMetadataFieldId())));
            if (active) throw CodeConfigurationService.rejection("active-binding", "Disable the field's rules before removing or changing its code role or trigger");
        }
    }
}
