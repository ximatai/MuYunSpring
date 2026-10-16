package net.ximatai.muyun.spring.demo.quality;

import net.ximatai.muyun.spring.demo.quality.configuration.*;
import net.ximatai.muyun.spring.demo.quality.equipment.EquipmentCategoryService;
import net.ximatai.muyun.spring.demo.quality.product.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;

class QualityDemoProfileBoundaryTest {
    @Test void sampleApplicationAndItsInstallersStayAbsentUnlessExplicitlyEnabled() {
        new ApplicationContextRunner().withUserConfiguration(Scan.class).run(context -> {
            assertThat(context).doesNotHaveBean(QualityApplication.class);
            assertThat(context).doesNotHaveBean(QualityDemoBootstrapTask.class);
            assertThat(context).doesNotHaveBean(QualityDemoMountTask.class);
            assertThat(context).doesNotHaveBean(QualityDemoPageTask.class);
            assertThat(context).doesNotHaveBean(QualityDemoMenuDeclarations.class);
            assertThat(context).doesNotHaveBean(EquipmentCategoryService.class);
            assertThat(context).doesNotHaveBean(ProductService.class);
        });
    }
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("net.ximatai.muyun.spring.demo.quality")
    static class Scan { }
}
