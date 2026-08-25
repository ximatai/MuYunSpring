package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.function.Supplier;

public interface ScopedWeb<S> {
    S service();

    /** Optional platform hook; generic adapters intentionally depend only on this stable seam. */
    default NavigatorReferenceQueryContextResolver navigatorReferenceQueryContextResolver() {
        return null;
    }

    default <T> T webScope(Supplier<T> action) {
        return webScope(webScopeName(), true, action);
    }

    private <T> T webScope(String scopeName, boolean verifyActiveTenant, Supplier<T> action) {
        if (TenantContext.isSystem()) {
            return action.get();
        }
        String tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new PlatformException(scopeName + " requires tenant context"));
        if (verifyActiveTenant && service() instanceof ActiveTenantVerifier activeTenantVerifier) {
            activeTenantVerifier.verifyActiveTenant(tenantId);
        }
        return action.get();
    }

    default String webScopeName() {
        RequestMapping mapping = AnnotationUtils.findAnnotation(getClass(), RequestMapping.class);
        if (mapping != null) {
            String mappingPath = firstText(mapping.value());
            if (mappingPath == null) {
                mappingPath = firstText(mapping.path());
            }
            if (mappingPath != null) {
                return mappingPath.replaceFirst("^/", "");
            }
        }
        if (service() instanceof CrudAbility<?> crudAbility) {
            String moduleAlias = crudAbility.getModuleAlias();
            if (moduleAlias != null && !moduleAlias.isBlank()) {
                return moduleAlias;
            }
        }
        return service().getClass().getSimpleName();
    }

    private static String firstText(String[] values) {
        if (values == null || values.length == 0 || values[0].isBlank()) {
            return null;
        }
        return values[0];
    }
}
