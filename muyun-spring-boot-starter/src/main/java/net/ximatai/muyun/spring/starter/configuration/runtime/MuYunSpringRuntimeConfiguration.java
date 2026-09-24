package net.ximatai.muyun.spring.starter.configuration.runtime;

import net.ximatai.muyun.spring.common.runtime.PlatformRuntimeModeProvider;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.time.BusinessCalendarService;
import net.ximatai.muyun.spring.common.time.BusinessTimeZoneResolver;
import net.ximatai.muyun.spring.common.time.NaturalBusinessCalendarService;
import net.ximatai.muyun.spring.common.time.PlatformTimeService;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 平台运行模式装配：将配置属性转换为 Schema 等治理能力可消费的运行态门面。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MuYunSpringRuntimeProperties.class, MuYunSpringPlatformTimeProperties.class})
public class MuYunSpringRuntimeConfiguration {
    @Bean
    @ConditionalOnMissingBean
    /** 允许宿主注入测试时钟或业务时钟；默认保持 JVM 本地时钟。 */
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    @ConditionalOnMissingBean
    /** 统一业务时区解析，供静态查询、动态记录和日历共用。 */
    PlatformTimeService platformTimeService(ObjectProvider<Clock> clockProvider,
                                            ObjectProvider<BusinessTimeZoneResolver> zoneResolvers,
                                            MuYunSpringPlatformTimeProperties timeProperties) {
        Clock clock = clockProvider == null ? null : clockProvider.getIfAvailable();
        ZoneId defaultZoneId = defaultZoneId(timeProperties);
        return new PlatformTimeService(
                clock,
                defaultZoneId,
                zoneResolvers == null ? null : zoneResolvers.orderedStream().toList()
        );
    }

    private ZoneId defaultZoneId(MuYunSpringPlatformTimeProperties timeProperties) {
        String configured = timeProperties == null ? null : timeProperties.getDefaultZoneId();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return PlatformTimeService.requireIanaZoneId(configured);
    }

    @Bean
    @ConditionalOnMissingBean
    /** 默认日历服务只解释时间语义；节假日等业务规则可由领域覆盖。 */
    BusinessCalendarService businessCalendarService(PlatformTimeService platformTimeService) {
        return new NaturalBusinessCalendarService(platformTimeService);
    }

    @Bean(destroyMethod = "close")
    AutoCloseable abilityTimePolicy(PlatformTimeService timeService) {
        return PlatformAbilityRuntime.configureTimeService(() -> timeService);
    }

    @Bean
    @ConditionalOnMissingBean
    /** 应用可覆盖此 Bean，以接入部署环境自己的运行模式决策。 */
    PlatformRuntimeModeProvider platformRuntimeModeProvider(MuYunSpringRuntimeProperties properties) {
        return new PropertiesPlatformRuntimeModeProvider(properties);
    }
}
