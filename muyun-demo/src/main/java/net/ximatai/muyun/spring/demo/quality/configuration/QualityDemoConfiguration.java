package net.ximatai.muyun.spring.demo.quality.configuration;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.*;

/** Optional business sample; never installs sample business modules in the standard application. */
@AutoConfiguration
@Profile("collaboration-demo")
@Import(QualityApplication.class)
@ComponentScan("net.ximatai.muyun.spring.demo.quality")
@EnableMuYunRepositories(basePackages = "net.ximatai.muyun.spring.demo.quality")
public class QualityDemoConfiguration { }
