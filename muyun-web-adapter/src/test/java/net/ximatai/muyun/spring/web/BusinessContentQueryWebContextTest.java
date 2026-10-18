package net.ximatai.muyun.spring.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.*;

class BusinessContentQueryWebContextTest {
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test void canonicalizesABoundedExplicitSnapshotWithoutChangingTheSingleSortLanguage() {
        var request = new MockHttpServletRequest();
        request.addHeader(BusinessContentQueryWebContext.LANGUAGE_HEADER, "DE");
        request.addHeader(BusinessContentQueryWebContext.LANGUAGES_HEADER, "zh-cn,en,de");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(BusinessContentQueryWebContext.languages()).containsExactly("de", "zh-CN", "en");
        assertThat(BusinessContentQueryWebContext.language()).contains("de");
    }

    @Test void absentHeadersDoNotRequestTranslationsAndMalformedOrExcessiveBatchesAreRejected() {
        assertThat(BusinessContentQueryWebContext.languages()).isEmpty();
        for (String value : new String[] {"en,", "en,,de", "en,de,fr,it,es,pt,nl,sv,pl", "x".repeat(2049)}) {
            var request = new MockHttpServletRequest(); request.addHeader(BusinessContentQueryWebContext.LANGUAGES_HEADER, value);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            assertThatIllegalArgumentException().isThrownBy(BusinessContentQueryWebContext::languages);
        }
    }
}
