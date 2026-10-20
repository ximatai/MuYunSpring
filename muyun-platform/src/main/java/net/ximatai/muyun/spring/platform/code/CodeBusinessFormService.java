package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.code.*;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

/** Uses installed declarations and the same selector and renderer as saving, for root and direct-child forms. */
@Service
public class CodeBusinessFormService {
    private final CodeTargetCatalog statics;
    private final Optional<DynamicRecordService> dynamics;
    private final CodeRuleService rules;
    private final CodeGenerateService generation;
    private final CodeBusinessTimeService time;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final StaticEntityDefinitionCompiler compiler = new StaticEntityDefinitionCompiler();
    public CodeBusinessFormService(CodeTargetCatalog statics, Optional<DynamicRecordService> dynamics,
                                  CodeRuleService rules, CodeGenerateService generation, CodeBusinessTimeService time, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.statics = statics; this.dynamics = dynamics; this.rules = rules; this.generation = generation; this.time = time; this.mapper = mapper;
    }
    private record Target(String module, EntityDefinition entity, CrudAbility<?> service, Target parent) { }

    public List<CodeBusinessFieldState> fields(String moduleAlias, CodeBusinessFormRequest request) {
        var input = request == null ? new CodeBusinessFormRequest(null, null, null) : request;
        Target target = target(moduleAlias, input.entityAlias());
        Map<String, Object> context = values(target.entity(), input.record());
        String organization = organization(target, context, input.record().get("id"));
        qualify(context, target.entity().alias(), new LinkedHashMap<>(context));
        if (target.parent() != null) {
            Map<String, Object> parentValues = values(target.parent().entity(), input.parent());
            String parentOrg = organization(target.parent(), parentValues, input.parent().get("id"));
            if (organization == null) organization = parentOrg;
            // Parent candidates stay inside this preview context. They are never written back into either draft.
            qualify(parentValues, target.parent().entity().alias(), new LinkedHashMap<>(parentValues));
            evaluate(target.parent().module(), target.parent().entity(), parentValues, parentOrg, time.resolveBusinessLocalDateTime(parentOrg, (LocalDateTime) null));
            qualify(context, target.parent().entity().alias(), parentValues);
        }
        return evaluate(target.module(), target.entity(), context, organization, time.resolveBusinessLocalDateTime(organization, (LocalDateTime) null));
    }
    private List<CodeBusinessFieldState> evaluate(String module, EntityDefinition entity, Map<String, Object> context, String organization, LocalDateTime at) {
        var resolved = rules.resolveRules(new ResolveCodeRuleCommand(module, entity.alias(), null, null, organization, at)).stream()
                .filter(rule -> entity.businessCodes().containsKey(rule.rule().getFieldName())).toList();
        var result = new LinkedHashMap<String, CodeBusinessFieldState>();
        for (var selected : new CodeDependencyResolver().orderedRules(resolved, entity.alias())) {
            var rule = selected.rule(); var declaration = entity.businessCodes().get(rule.getFieldName());
            String candidate = null;
            if (declaration.trigger() == CodeTrigger.SAVE && rule.getMode() != CodeMode.MANUAL
                    && text(context.get(rule.getFieldName())) == null) {
                try {
                    candidate = generation.previewResolved(selected, context, at).value();
                    context.put(rule.getFieldName(), candidate);
                    context.put(entity.alias() + "." + rule.getFieldName(), candidate);
                } catch (IllegalArgumentException | PlatformException incompleteDraft) {
                    // Missing draft sources can prevent a candidate while the numbering mode is already authoritative.
                }
            }
            result.put(rule.getFieldName(), new CodeBusinessFieldState(rule.getFieldName(), rule.getMode(), declaration.trigger(),
                    declaration.trigger() == CodeTrigger.EXPLICIT || rule.getMode() == CodeMode.AUTO, true, candidate));
        }
        entity.businessCodes().forEach((name, declaration) -> result.putIfAbsent(name,
                new CodeBusinessFieldState(name, null, declaration.trigger(), true, false, null)));
        return result.values().stream().sorted(Comparator.comparing(CodeBusinessFieldState::fieldName)).toList();
    }
    private Target target(String moduleAlias, String entityAlias) {
        var root = statics.staticModule(moduleAlias);
        if (root.isPresent()) {
            var owner = root.get(); var main = definition(owner);
            if (entityAlias == null || entityAlias.equals(main.alias())) return new Target(moduleAlias, main, owner, null);
            if (owner instanceof ChildrenAbility<?> aggregate) {
                var child = aggregate.childRelations().stream().filter(relation -> entityAlias.equals(relation.relationCode())).findFirst()
                        .orElseThrow(() -> new PlatformException("Unknown direct child code form target"));
                return new Target(child.childAbility().getModuleAlias(), definition(child.childAbility()), child.childAbility(), new Target(moduleAlias, main, owner, null));
            }
            throw new PlatformException("Unknown code form entity");
        }
        ModuleDefinition module = dynamics.stream().flatMap(service -> service.moduleDefinitions().stream())
                .filter(candidate -> moduleAlias.equals(candidate.moduleAlias())).findFirst()
                .orElseThrow(() -> new PlatformException("Unknown code form module"));
        String alias = entityAlias == null ? module.mainEntityAlias() : entityAlias;
        var entity = module.entities().stream().filter(candidate -> alias.equals(candidate.alias())).findFirst()
                .orElseThrow(() -> new PlatformException("Unknown code form entity"));
        if (alias.equals(module.mainEntityAlias())) return new Target(moduleAlias, entity, null, null);
        var relation = module.relations().stream().filter(candidate -> alias.equals(candidate.childEntityAlias())
                && module.mainEntityAlias().equals(candidate.parentEntityAlias())).findFirst()
                .orElseThrow(() -> new PlatformException("Code forms support declared direct children only"));
        var parent = module.entities().stream().filter(candidate -> relation.parentEntityAlias().equals(candidate.alias())).findFirst().orElseThrow();
        return new Target(moduleAlias, entity, null, new Target(moduleAlias, parent, null, null));
    }
    private EntityDefinition definition(CrudAbility<?> service) {
        String alias = service instanceof CodeAbility<?> code ? code.codeEntityAlias()
                : service.getModuleAlias().substring(service.getModuleAlias().lastIndexOf('.') + 1);
        return compiler.compile(alias, alias, service.modelClass());
    }
    @SuppressWarnings("unchecked")
    private String organization(Target target, Map<String, Object> values, Object id) {
        String organization = null;
        if (target.service() instanceof CodeAbility<?> code) {
            Map<String, Object> modelValues = new LinkedHashMap<>();
            var persisted = StaticCodeFields.persistedFields(code.modelClass());
            values.forEach((name, value) -> { if (persisted.containsKey(name)) modelValues.put(name, value); });
            if (id != null && persisted.containsKey("id")) modelValues.put("id", id);
            var model = (net.ximatai.muyun.spring.common.model.contract.EntityContract) mapper.convertValue(modelValues, code.modelClass());
            organization = ((CodeAbility<net.ximatai.muyun.spring.common.model.contract.EntityContract>) code).codeOrganizationId(model);
        } else if (target.entity().supports(net.ximatai.muyun.spring.common.platform.EntityCapability.DATA_SCOPE))
            organization = text(values.get("authOrganizationId"));
        boolean scoped = target.service() instanceof net.ximatai.muyun.spring.ability.DataScopeAbility<?>
                || target.service() == null && target.entity().supports(net.ximatai.muyun.spring.common.platform.EntityCapability.DATA_SCOPE);
        if (organization == null && id == null && scoped && target.parent() == null)
            organization = CurrentUserContext.currentUser().map(CurrentUser::organizationId).orElse(null);
        return organization;
    }
    private Map<String, Object> values(EntityDefinition entity, Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        entity.fields().stream().filter(FieldDefinition::isPhysical).forEach(field -> {
            if (input.containsKey(field.fieldName())) result.put(field.fieldName(), field.behavior().writeRules().normalize(input.get(field.fieldName())));
        });
        // Static and dynamic compilation keep organization storage facts out of some public business-field catalogs.
        if (input.containsKey("authOrganizationId")) result.put("authOrganizationId", input.get("authOrganizationId"));
        return result;
    }
    private void qualify(Map<String, Object> target, String alias, Map<String, Object> values) {
        values.forEach((name, value) -> { if (!name.contains(".")) target.put(alias + "." + name, value); });
    }
    private String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }
}
