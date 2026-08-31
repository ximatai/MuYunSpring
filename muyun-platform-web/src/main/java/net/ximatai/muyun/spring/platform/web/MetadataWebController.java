package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = MetadataService.MODULE_ALIAS, title = "平台元数据")
@StaticModuleOpenApi
@RequestMapping("/platform.metadata")
public class MetadataWebController extends WebSupport<MetadataService> implements
        CrudWeb<Metadata, MetadataService>,
        SystemScope<MetadataService> {
}
