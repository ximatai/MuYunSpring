package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.common.i18n.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import java.util.Objects;
import java.util.stream.Stream;

/** IAM-owned display projection; scope keys, authorized directory facts and original labels stay intact. */
@RestControllerAdvice(assignableTypes = RoleWebController.class)
public final class RoleScopePresentationResponseAdvice implements ResponseBodyAdvice<Object> {
    private final ObjectProvider<TextResolver> resolvers;
    private final ObjectProvider<LanguageCatalog> catalogs;
    public RoleScopePresentationResponseAdvice(ObjectProvider<TextResolver> resolvers, ObjectProvider<LanguageCatalog> catalogs) {
        this.resolvers = resolvers;
        this.catalogs = catalogs;
    }
    @Override public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) {
        return RoleScopeSelectionFacade.RoleScopeSelectionDescriptor.class.isAssignableFrom(parameter.getParameterType());
    }
    @Override public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        return project(body);
    }
    Object project(Object body) {
        var resolver = resolvers.getIfAvailable();
        var catalog = catalogs.getIfAvailable();
        if (!(body instanceof RoleScopeSelectionFacade.RoleScopeSelectionDescriptor descriptor) || resolver == null || catalog == null) return body;
        var sources = Stream.concat(descriptor.directSelections().stream().map(RoleScopeSelectionFacade.DirectSelection::titleSource),
                descriptor.navigations().stream().map(RoleScopeSelectionFacade.Navigation::titleSource)).filter(Objects::nonNull).toList();
        var prepared = resolver.prepare(sources);
        return new RoleScopeSelectionFacade.RoleScopeSelectionDescriptor(
                descriptor.directSelections().stream().map(selection -> new RoleScopeSelectionFacade.DirectSelection(
                        selection.selectionKey(), selection.title(), selection.titleSource(),
                        presentation(selection.title(), selection.titleSource(), prepared, catalog))).toList(),
                descriptor.navigations().stream().map(navigation -> new RoleScopeSelectionFacade.Navigation(
                        navigation.navigationKey(), navigation.level(), navigation.title(), navigation.titleSource(),
                        presentation(navigation.title(), navigation.titleSource(), prepared, catalog))).toList());
    }
    private static TextPresentation presentation(String original, TextReference source, TextResolver prepared, LanguageCatalog catalog) {
        return source != null && source.sourceKind() == TextReference.SourceKind.CODE && source.sourceId().startsWith("iam.role.scope.") &&
                source.originalText().equals(original) && source.sourceVersion().equals(TextReference.versionOf(original))
                ? TextPresentations.of(source, java.util.Map.of(), prepared, catalog) : null;
    }
}
