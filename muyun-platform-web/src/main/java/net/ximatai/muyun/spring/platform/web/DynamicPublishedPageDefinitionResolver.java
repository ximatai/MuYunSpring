package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionResolver;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Resolves the first executable page-composition source for dynamic modules.
 *
 * <p>The initial runtime cut deliberately accepts only the global Web presentation. Tenant and
 * organization variants remain a domain capability until the execution-plan catalog becomes
 * request-scope aware.</p>
 */
@Service
public class DynamicPublishedPageDefinitionResolver {
    public static final String MANAGEMENT_PAGE_ALIAS = "management";

    private final PlatformPageDefinitionService pageService;
    private final PlatformPresentationRevisionResolver revisionResolver;

    public DynamicPublishedPageDefinitionResolver(PlatformPageDefinitionService pageService,
                                                  PlatformPresentationRevisionResolver revisionResolver) {
        this.pageService = pageService;
        this.revisionResolver = revisionResolver;
    }

    public Optional<ResolvedPublishedPage> resolveWebGlobal(DynamicModuleDescriptor module) {
        if (module == null) return Optional.empty();
        return pageService.resolveGlobalPage(module.moduleAlias(), MANAGEMENT_PAGE_ALIAS)
                .flatMap(page -> revisionResolver.resolve(page.getId(), PlatformPresentationClientType.WEB, null, null)
                        .map(revision -> new ResolvedPublishedPage(page, revision, compile(page, revision, module))));
    }

    private ModuleUiDefinition compile(PlatformPageDefinition page,
                                       PlatformPresentationRevision revision,
                                       DynamicModuleDescriptor module) {
        return PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page, revision,
                module.entities().stream().filter(entity -> module.mainEntityAlias().equals(entity.entityAlias()))
                        .findFirst().map(entity -> entity.fields().stream()
                                .map(net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor::fieldName)
                                .toList()).orElseThrow(() -> new IllegalStateException(
                                "dynamic runtime has no main entity: " + module.moduleAlias())));
    }

    /** The exact page revision used to compile a global Web runtime surface. */
    public record ResolvedPublishedPage(PlatformPageDefinition page,
                                        PlatformPresentationRevision revision,
                                        ModuleUiDefinition definition) {
    }
}
