package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.content.BusinessContentTextPresentation;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.web.BusinessContentQueryWebContext;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessContentRecordTitlePresentationTest {
    private final BusinessContentReadingProjectionService readings = mock(BusinessContentReadingProjectionService.class);
    private final BusinessContentRecordTitlePresentation presentation = new BusinessContentRecordTitlePresentation(readings);

    static class TitledRecord extends StandardTitledEntity { }

    @AfterEach void reset() { RequestContextHolder.resetRequestAttributes(); }

    @Test @SuppressWarnings("unchecked") void projectsOnlyTheExposedTitleFromTheAuthorizedRowUsingTheSharedReader() {
        var request = new MockHttpServletRequest();
        request.addHeader(BusinessContentQueryWebContext.LANGUAGES_HEADER, "en,de");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        CrudAbility<StandardTitledEntity> owner = mock(CrudAbility.class);
        var row = new TitledRecord(); row.setId("r1"); row.setTitle("原名");
        var label = new BusinessContentTextPresentation("原名", Map.of("de", "Originalname"));
        when(readings.fields(owner)).thenReturn(Set.of("title", "description"));
        when(readings.presentations(owner, Map.of("r1", Map.of("title", "原名")), Set.of("title"), List.of("en", "de")))
                .thenReturn(Map.of("r1", Map.of("title", label)));
        assertThat(presentation.project(owner, row, "title", "原名")).isSameAs(label);
        var order = inOrder(readings);
        order.verify(readings).fields(owner);
        order.verify(readings).capture(owner, List.of(row));
        order.verify(readings).presentations(owner, Map.of("r1", Map.of("title", "原名")), Set.of("title"), List.of("en", "de"));
        verifyNoMoreInteractions(readings);
        assertThat(row.getTitle()).isEqualTo("原名");
    }

    @Test @SuppressWarnings("unchecked") void noReadingIntentDoesNotQueryContent() {
        assertThat(presentation.project(mock(CrudAbility.class), new TitledRecord(), "title", "原名")).isNull();
        verifyNoInteractions(readings);
    }
}
