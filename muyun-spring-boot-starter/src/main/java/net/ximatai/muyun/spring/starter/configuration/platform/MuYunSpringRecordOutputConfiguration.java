package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.ability.option.StaticOptionLoadPopulator;
import net.ximatai.muyun.spring.ability.output.DefaultPlatformRecordOutput;
import net.ximatai.muyun.spring.ability.output.FieldProtectionRecordOutputTransformer;
import net.ximatai.muyun.spring.ability.output.OptionLoadRecordOutputTransformer;
import net.ximatai.muyun.spring.ability.output.PlatformRecordOutput;
import net.ximatai.muyun.spring.ability.output.RecordOutputTransformer;
import net.ximatai.muyun.spring.web.WebOutputSupport;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;

/**
 * 记录输出装配：按确定顺序组合标题补齐与字段保护，再桥接给 HTTP 输出层。
 * 业务 Service 仍只返回领域记录，不直接依赖 Web 序列化细节。
 */
@Configuration(proxyBeanMethods = false)
public class MuYunSpringRecordOutputConfiguration {
    @Bean
    @Order(0)
    /** 先补齐声明的选项投影，使后续输出转换器可基于完整语义处理记录。 */
    RecordOutputTransformer optionLoadRecordOutputTransformer(
            ObjectProvider<StaticOptionLoadPopulator> loadPopulatorProvider) {
        return new OptionLoadRecordOutputTransformer(
                loadPopulatorProvider.getIfAvailable(() -> StaticOptionLoadPopulator.NONE)
        );
    }

    @Bean
    @Order(10)
    RecordOutputTransformer auditReferenceRecordOutputTransformer() {
        return new net.ximatai.muyun.spring.ability.output.AuditReferenceRecordOutputTransformer();
    }

    @Bean
    @Order(100)
    /** 标题补齐后再执行字段保护，避免受保护字段以派生标题形式泄露。 */
    RecordOutputTransformer fieldProtectionRecordOutputTransformer() {
        return new FieldProtectionRecordOutputTransformer();
    }

    @Bean
    @ConditionalOnMissingBean
    /** 聚合有序转换器为统一输出门面，静态与动态读取均可复用。 */
    PlatformRecordOutput platformRecordOutput(ObjectProvider<RecordOutputTransformer> transformerProvider) {
        List<RecordOutputTransformer> transformers = transformerProvider.orderedStream().toList();
        return new DefaultPlatformRecordOutput(transformers);
    }

    @Bean
    /** 将平台输出门面安装到 Web adapter 的轻量桥接点。 */
    WebOutputSupportRegistration webOutputSupportRegistration(PlatformRecordOutput recordOutput) {
        return new WebOutputSupportRegistration(recordOutput);
    }

    static final class WebOutputSupportRegistration implements DisposableBean {
        WebOutputSupportRegistration(PlatformRecordOutput recordOutput) {
            WebOutputSupport.configure(recordOutput);
        }

        @Override
        public void destroy() {
            WebOutputSupport.reset();
        }
    }
}
