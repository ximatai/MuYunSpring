package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.LanguageContext;
import net.ximatai.muyun.spring.common.i18n.TextPresentation;
import net.ximatai.muyun.spring.common.i18n.TextResolver;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/** HTTP display projection of explicitly compiled source facts; never modifies a shared plan or a domain result. */
@RestControllerAdvice
public class ModulePresentationResponseAdvice implements ResponseBodyAdvice<Object> {
    private final ObjectMapper mapper;
    private final Supplier<TextResolver> textProvider;
    private final Supplier<LanguageCatalog> catalogProvider;

    public ModulePresentationResponseAdvice(ObjectMapper mapper, TextResolver texts, LanguageCatalog catalog) {
        this.mapper = mapper;
        this.textProvider = () -> texts;
        this.catalogProvider = () -> catalog;
    }

    @Autowired
    public ModulePresentationResponseAdvice(ObjectMapper mapper, ObjectProvider<TextResolver> texts,
                                            ObjectProvider<LanguageCatalog> catalog) {
        this.mapper = mapper;
        this.textProvider = texts::getIfAvailable;
        this.catalogProvider = catalog::getIfAvailable;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return PlatformModuleRuntimeContext.class.isAssignableFrom(returnType.getParameterType())
                || PresentationRevisionPreview.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof PlatformModuleRuntimeContext context) return project(context);
        if (body instanceof PresentationRevisionPreview preview) return project(preview);
        return body;
    }

    Object project(PlatformModuleRuntimeContext context) {
        return project(context, context.uiDescriptor(), context.actions(), context.titleSource(), context.title());
    }

    Object project(PresentationRevisionPreview preview) {
        return project(preview, preview.uiDescriptor(), java.util.List.of(), null, null);
    }

    private Object project(Object document, ResolvedModuleUiDescriptor descriptor,
                           java.util.List<PlatformModuleRuntimeAction> actions,
                           net.ximatai.muyun.spring.common.i18n.TextReference titleSource, String title) {
        TextResolver texts = textProvider.get();
        LanguageCatalog catalog = catalogProvider.get();
        ObjectNode output = mapper.valueToTree(document);
        Map<String, TextPresentation> bindings = new LinkedHashMap<>();
        Map<String, net.ximatai.muyun.spring.common.i18n.TextReference> visibleSources = new LinkedHashMap<>();
        var locations = descriptor == null ? java.util.List.<ModuleUiTextLocations.Location>of() : ModuleUiTextLocations.of(descriptor).stream().filter(location -> {
            var source = descriptor.sourceTexts().get(location.bindingKey());
            return source != null && source.originalText().equals(location.originalText()) &&
                    (!location.configured() || source.sourceKind() == net.ximatai.muyun.spring.common.i18n.TextReference.SourceKind.CONFIG);
        }).toList();
        var sources = new java.util.ArrayList<net.ximatai.muyun.spring.common.i18n.TextReference>();
        locations.stream().map(location -> descriptor.sourceTexts().get(location.bindingKey())).forEach(sources::add);
        actions.stream().filter(action -> action.titleSource() != null &&
                action.titleSource().originalText().equals(action.title())).map(PlatformModuleRuntimeAction::titleSource).forEach(sources::add);
        if (titleSource != null && titleSource.originalText().equals(title)) sources.add(titleSource);
        if (texts != null) texts = texts.prepare(sources);
        for (var location : locations) {
            var source = descriptor.sourceTexts().get(location.bindingKey());
            // An online override must obtain its own source fact; never borrow the underlying code translation.
            if (source == null || !source.originalText().equals(location.originalText()) ||
                    location.configured() && source.sourceKind() == net.ximatai.muyun.spring.common.i18n.TextReference.SourceKind.CODE) continue;
            Map<String, TextResources.Resolution> languages = new LinkedHashMap<>();
            if (texts != null && catalog != null) for (var language : catalog.languages()) if (language.enabled()) {
                languages.put(language.tag(), texts.resolve(source, Map.of(), language.tag()));
            }
            String path = "/uiDescriptor" + location.path();
            bindings.put(path, new TextPresentation(source, languages));
            visibleSources.put(location.bindingKey(), source);
            if (texts != null) replaceText(output, path, texts.resolve(source, Map.of(), LanguageContext.currentTag()).text());
        }
        for (int index = 0; index < actions.size(); index++) {
            var action = actions.get(index);
            var source = action.titleSource();
            if (source == null || !source.originalText().equals(action.title())) continue;
            Map<String, TextResources.Resolution> languages = new LinkedHashMap<>();
            if (texts != null && catalog != null) for (var language : catalog.languages()) if (language.enabled()) {
                languages.put(language.tag(), texts.resolve(source, Map.of(), language.tag()));
            }
            String path = "/actions/" + index + "/title";
            bindings.put(path, new TextPresentation(source, languages));
            if (texts != null) replaceText(output, path, texts.resolve(source, Map.of(), LanguageContext.currentTag()).text());
        }
        if (titleSource != null && titleSource.originalText().equals(title)) {
            var source = titleSource;
            var languages = new LinkedHashMap<String, TextResources.Resolution>();
            if (texts != null && catalog != null) for (var language : catalog.languages()) if (language.enabled())
                languages.put(language.tag(), texts.resolve(source, Map.of(), language.tag()));
            bindings.put("/title", new TextPresentation(source, languages));
            if (texts != null) replaceText(output, "/title", texts.resolve(source, Map.of(), LanguageContext.currentTag()).text());
        }
        output.set("textBindings", mapper.valueToTree(bindings));
        // A compiled directory can contain slots removed by request authorization or source resolution.
        if (descriptor != null) ((ObjectNode) output.get("uiDescriptor")).set("sourceTexts", mapper.valueToTree(visibleSources));
        return mapper.convertValue(output, new TypeReference<Map<String, Object>>() { });
    }

    private void replaceText(ObjectNode root, String path, String value) {
        int separator = path.lastIndexOf('/');
        JsonNode parent = root.at(path.substring(0, separator));
        if (parent instanceof ObjectNode object) object.put(path.substring(separator + 1), value);
    }
}
