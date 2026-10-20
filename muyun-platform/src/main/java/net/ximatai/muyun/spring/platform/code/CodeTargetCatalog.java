package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.code.CodeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;

/** Static code targets come from the existing service registry, never fabricated metadata. */
@Service
public final class CodeTargetCatalog {
    private final ObjectProvider<CrudAbility<?>> services;
    public CodeTargetCatalog(ObjectProvider<CrudAbility<?>> services) { this.services = Objects.requireNonNull(services); }
    public Optional<CodeAbility<?>> find(String moduleAlias, String entityAlias) {
        List<CodeAbility<?>> matches = services.orderedStream().filter(CodeAbility.class::isInstance)
                .<CodeAbility<?>>map(service -> (CodeAbility<?>) service)
                .filter(service -> moduleAlias.equals(service.getModuleAlias()) && entityAlias.equals(service.codeEntityAlias())).toList();
        if (matches.size() > 1) throw new PlatformException("Duplicate static business code target: " + moduleAlias + "/" + entityAlias);
        return matches.stream().findFirst();
    }
    public Optional<CrudAbility<?>> staticModule(String moduleAlias) {
        var matches = services.orderedStream().filter(service -> moduleAlias.equals(service.getModuleAlias())).toList();
        if (matches.size() > 1) throw new PlatformException("Duplicate static module service: " + moduleAlias);
        return matches.stream().findFirst();
    }
    public record ParentTarget(CrudAbility<?> service, net.ximatai.muyun.spring.ability.child.ChildRelation<?, ?> relation) {
        public String entityAlias() {
            return service instanceof CodeAbility<?> coded ? coded.codeEntityAlias()
                    : service.getModuleAlias().substring(service.getModuleAlias().lastIndexOf('.') + 1);
        }
    }
    public Optional<ParentTarget> parentOf(CodeAbility<?> child) {
        List<ParentTarget> matches = new ArrayList<>();
        services.orderedStream().filter(net.ximatai.muyun.spring.ability.child.ChildrenAbility.class::isInstance).forEach(service -> {
            var parent = (net.ximatai.muyun.spring.ability.child.ChildrenAbility<?>) service;
            parent.childRelations().stream().filter(relation -> relation.childAbility().getModuleAlias().equals(child.getModuleAlias())
                    && relation.childAbility().modelClass().equals(child.modelClass())).forEach(relation -> matches.add(new ParentTarget(service, relation)));
        });
        if (matches.size() > 1) throw new PlatformException("Business code parent must resolve uniquely: " + child.getModuleAlias());
        return matches.stream().findFirst();
    }
    public List<CodeAbility<?>> staticTargets() {
        return services.orderedStream().filter(CodeAbility.class::isInstance).<CodeAbility<?>>map(service -> (CodeAbility<?>) service).toList();
    }
}
