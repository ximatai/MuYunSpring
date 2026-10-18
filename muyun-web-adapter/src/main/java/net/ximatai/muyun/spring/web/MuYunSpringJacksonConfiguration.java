package net.ximatai.muyun.spring.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleDeserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MuYunSpringJacksonConfiguration {
    @Bean
    public Module codeTitleEnumJacksonModule() {
        SimpleModule module = new SimpleModule("codeTitleEnum");
        module.addSerializer(CodeTitleEnum.class, new CodeTitleEnumJsonSerializer());
        module.setDeserializers(new CodeTitleEnumDeserializers());
        module.setMixInAnnotation(CurrentUser.class, CurrentUserJsonMixin.class);
        module.setMixInAnnotation(net.ximatai.muyun.spring.common.content.ContentLanguageInputs.class, ContentLanguageInputsMixin.class);
        return module;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private abstract static class CurrentUserJsonMixin {
    }

    private abstract static class ContentLanguageInputsMixin {
        @com.fasterxml.jackson.annotation.JsonProperty("$contentLanguages")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public abstract java.util.Map<String, net.ximatai.muyun.spring.common.content.ContentWriteContext.SourceInput> contentLanguageInputs();
        @com.fasterxml.jackson.annotation.JsonProperty("$contentLanguages")
        public abstract void contentLanguageInputs(java.util.Map<String, net.ximatai.muyun.spring.common.content.ContentWriteContext.SourceInput> inputs);
    }

    private static final class CodeTitleEnumDeserializers extends SimpleDeserializers {
        @Override
        public JsonDeserializer<?> findEnumDeserializer(Class<?> type,
                                                        DeserializationConfig config,
                                                        BeanDescription beanDesc) {
            if (type != null && CodeTitleEnum.class.isAssignableFrom(type)) {
                return new CodeTitleEnumJsonDeserializer(type);
            }
            return null;
        }
    }
}
