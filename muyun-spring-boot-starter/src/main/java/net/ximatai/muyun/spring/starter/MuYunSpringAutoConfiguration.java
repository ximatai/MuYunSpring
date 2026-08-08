package net.ximatai.muyun.spring.starter;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.starter.bootstrap.MuYunSpringBootstrapConfiguration;
import net.ximatai.muyun.spring.starter.configuration.database.MuYunSpringDatabaseConfiguration;
import net.ximatai.muyun.spring.starter.configuration.dynamic.MuYunSpringDynamicRuntimeConfiguration;
import net.ximatai.muyun.spring.starter.configuration.filetransfer.MuYunFileServerTransferConfiguration;
import net.ximatai.muyun.spring.starter.configuration.iam.MuYunSpringIdentityConfiguration;
import net.ximatai.muyun.spring.starter.configuration.iam.MuYunSpringIdentityWebConfiguration;
import net.ximatai.muyun.spring.starter.configuration.platform.MuYunSpringDeletionConfiguration;
import net.ximatai.muyun.spring.starter.configuration.platform.MuYunSpringFileReferenceConfiguration;
import net.ximatai.muyun.spring.starter.configuration.platform.MuYunSpringOptionConfiguration;
import net.ximatai.muyun.spring.starter.configuration.platform.MuYunSpringRecordOutputConfiguration;
import net.ximatai.muyun.spring.starter.configuration.platform.MuYunSpringReferenceConfiguration;
import net.ximatai.muyun.spring.starter.configuration.runtime.MuYunSpringRuntimeEventHandlerConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MuYunSpring 的应用装配入口。
 *
 * <p>平台组件由 Starter 扫描，业务应用仍只扫描自己的包；两者不相互侵入。</p>
 */
@AutoConfiguration
@ComponentScan(
        basePackages = {
                "net.ximatai.muyun.spring.dynamic",
                "net.ximatai.muyun.spring.platform",
                "net.ximatai.muyun.spring.iam",
                "net.ximatai.muyun.spring.web"
        },
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
        }
)
@EnableMuYunRepositories(basePackages = {
        "net.ximatai.muyun.spring.dynamic",
        "net.ximatai.muyun.spring.platform",
        "net.ximatai.muyun.spring.iam"
})
@EnableScheduling
@Import({
        MuYunSpringDatabaseConfiguration.class,
        MuYunSpringDynamicRuntimeConfiguration.class,
        MuYunFileServerTransferConfiguration.class,
        MuYunSpringIdentityConfiguration.class,
        MuYunSpringIdentityWebConfiguration.class,
        MuYunSpringDeletionConfiguration.class,
        MuYunSpringFileReferenceConfiguration.class,
        MuYunSpringOptionConfiguration.class,
        MuYunSpringRecordOutputConfiguration.class,
        MuYunSpringReferenceConfiguration.class,
        MuYunSpringRuntimeEventHandlerConfiguration.class,
        MuYunSpringBootstrapConfiguration.class
})
public class MuYunSpringAutoConfiguration {
}
