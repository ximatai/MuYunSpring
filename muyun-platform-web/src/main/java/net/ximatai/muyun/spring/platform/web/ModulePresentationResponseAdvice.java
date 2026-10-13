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
        return PlatformModuleRuntimeContext.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof PlatformModuleRuntimeContext context) || context.uiDescriptor() == null) return body;
        return project(context);
    }

    Object project(PlatformModuleRuntimeContext context) {
        TextResolver texts = textProvider.get();
        LanguageCatalog catalog = catalogProvider.get();
        ObjectNode output = mapper.valueToTree(context);
        Map<String, TextPresentation> bindings = new LinkedHashMap<>();
        Map<String, net.ximatai.muyun.spring.common.i18n.TextReference> visibleSources = new LinkedHashMap<>();
        var descriptor = context.uiDescriptor();
        if (descriptor == null) return context;
        var locations = ModuleUiTextLocations.of(descriptor).stream().filter(location -> {
            var source = descriptor.sourceTexts().get(location.bindingKey());
            return source != null && source.originalText().equals(location.originalText()) &&
                    (!location.configured() || source.sourceKind() == net.ximatai.muyun.spring.common.i18n.TextReference.SourceKind.CONFIG);
        }).toList();
        if (texts != null) texts = texts.prepare(locations.stream().map(location -> descriptor.sourceTexts().get(location.bindingKey())).toList());
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
        output.set("textBindings", mapper.valueToTree(bindings));
        // A compiled directory can contain slots removed by request authorization or source resolution.
        ((ObjectNode) output.get("uiDescriptor")).set("sourceTexts", mapper.valueToTree(visibleSources));
        return mapper.convertValue(output, new TypeReference<Map<String, Object>>() { });
    }

    private void replaceText(ObjectNode root, String path, String value) {
        int separator = path.lastIndexOf('/');
        JsonNode parent = root.at(path.substring(0, separator));
        if (parent instanceof ObjectNode object) object.put(path.substring(separator + 1), value);
    }
}
