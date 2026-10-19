package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.ability.code.CodeAbility;
import net.ximatai.muyun.spring.ability.capability.*;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import java.util.*;

/** Coding participates in standard saves; explicit issuance remains a trusted domain operation. */
public final class BusinessCodeCapabilityModule implements CapabilityModule, CapabilityActionContribution, DynamicCapabilityDefinitionFacet {
    public EntityCapability capability() { return EntityCapability.BUSINESS_CODE; }
    public Set<EntityCapability> dependencies() { return Set.of(EntityCapability.CRUD); }
    public StaticCapabilityDeclarationPolicy declarationPolicy() { return StaticCapabilityDeclarationPolicy.SERVICE_ONLY; }
    public CapabilityActionContribution actionContribution() { return this; }
    public List<PlatformAction> standardActions() { return List.of(); }
    public Optional<CapabilityEndpointProjection> endpointProjection(PlatformAction action) { return Optional.empty(); }
    public Optional<StaticCapabilityFacet> staticFacet() { return Optional.of(service -> {
        if (!(service instanceof CodeAbility<?> code)) return false;
        if (net.ximatai.muyun.spring.ability.code.StaticCodeFields.declarations(code.modelClass()).isEmpty())
            throw new IllegalArgumentException("CodeAbility requires at least one @BusinessCode field");
        return true;
    }); }
    public Optional<DynamicCapabilityDefinitionFacet> dynamicDefinitionFacet() { return Optional.of(this); }
    public static boolean isEligible(FieldDefinition field) {
        String name = field.fieldName();
        boolean managed = PlatformFieldPolicy.find(name) != null || name.equals("parentId")
                || DynamicAbilityFields.dataScopeFields().stream().anyMatch(item -> item.fieldName().equals(name))
                || DynamicAbilityFields.approvalFields().stream().anyMatch(item -> item.fieldName().equals(name));
        return field.isPhysical() && field.type() == FieldType.STRING && !managed
                && !field.protection().enabled() && !field.content().enabled() && field.dictionaryBinding() == null;
    }
    public void validateDefinition(EntityDefinition entity) {
        if (entity.businessCodes().isEmpty()) throw new IllegalArgumentException("Business code capability requires field declarations");
        for (String name : entity.businessCodes().keySet()) {
            var field = entity.fields().stream().filter(item -> item.fieldName().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown business code field: " + name));
            var write = field.resolvedWriteRules();
            if (entity.businessCodes().get(name).trigger() == net.ximatai.muyun.spring.ability.code.CodeTrigger.EXPLICIT
                    && (write.nonNull() || write.requiredOnInsert() || write.requiredOnUpdate()))
                throw new IllegalArgumentException("EXPLICIT business code must allow unnumbered drafts: " + name);
            if (!isEligible(field))
                throw new IllegalArgumentException("Business code requires an unprotected physical string field: " + name);
        }
    }
}
