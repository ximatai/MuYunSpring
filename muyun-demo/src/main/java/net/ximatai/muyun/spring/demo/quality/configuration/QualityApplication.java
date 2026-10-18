package net.ximatai.muyun.spring.demo.quality.configuration;

import org.springframework.context.annotation.Profile;

import net.ximatai.muyun.spring.platform.application.PlatformStaticApplication;

/** Static masters and dynamic quality exceptions belong to the same business application. */
@Profile("collaboration-demo")
@PlatformStaticApplication(alias = "quality", title = "质量协作", sortOrder = 110)
public class QualityApplication { }
