package net.ximatai.muyun.spring.common.i18n;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;

class LanguageContextTest {
    @Test
    void restoresNestedScopesAndDoesNotLeakAcrossConcurrentOrReusedThreads() throws Exception {
        assertThat(LanguageContext.currentTag()).isNull();
        try (var ignored = LanguageContext.use("en")) {
            try (var nested = LanguageContext.use("zh-CN")) { assertThat(LanguageContext.currentTag()).isEqualTo("zh-CN"); }
            assertThat(LanguageContext.currentTag()).isEqualTo("en");
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> { try (var scope = LanguageContext.use("en")) { return LanguageContext.currentTag(); } });
                var second = executor.submit(() -> { try (var scope = LanguageContext.use("zh-CN")) { return LanguageContext.currentTag(); } });
                assertThat(first.get()).isEqualTo("en");
                assertThat(second.get()).isEqualTo("zh-CN");
                assertThat(executor.submit(LanguageContext::currentTag).get()).isNull();
            }
        }
        assertThat(LanguageContext.currentTag()).isNull();
    }
}
