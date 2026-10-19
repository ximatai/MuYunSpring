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
    public List<CodeAbility<?>> staticTargets() {
        return services.orderedStream().filter(CodeAbility.class::isInstance).<CodeAbility<?>>map(service -> (CodeAbility<?>) service).toList();
    }
}
