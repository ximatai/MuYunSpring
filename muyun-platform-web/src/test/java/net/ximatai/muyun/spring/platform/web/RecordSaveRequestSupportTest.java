package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.platform.save.RecordSaveReceipt;
import net.ximatai.muyun.spring.platform.save.RecordSaveReceiptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RecordSaveRequestSupportTest {
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }
    @SuppressWarnings("unchecked")
    private CrudWeb<Row, CrudAbility<Row>> controller() {
        var controller = (CrudWeb<Row, CrudAbility<Row>>) mock(CrudWeb.class);
        when(controller.webScopeName()).thenReturn("demo.record");
        return controller;
    }
    private MockHttpServletRequest bind(RecordSaveReceiptService service) {
        var request = new MockHttpServletRequest();
        request.setAttribute(RecordSaveRequestSupport.ATTRIBUTE, new RecordSaveRequestSupport(service, new ObjectMapper().findAndRegisterModules()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }
    @Test void preservesOrdinarySaveAndRequiresInfrastructureForIdentifiedSave() {
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Row row = new Row();
        assertThat(RecordSaveRequestSupport.execute(controller(), "create", null, row, () -> row)).isSameAs(row);
        request.addHeader(RecordSaveRequestSupport.HEADER, "request-123456789");
        assertThatThrownBy(() -> RecordSaveRequestSupport.execute(controller(), "create", null, row, () -> row))
            .hasMessageContaining("unavailable");
    }
    @Test void fingerprintsReviewedPayloadAndScopeBeforeFormalSaveMutatesTheEntity() {
        var service = mock(RecordSaveReceiptService.class);
        var request = bind(service);
        request.addHeader(RecordSaveRequestSupport.HEADER, "request-123456789");
        request.addHeader("X-MuYun-Page-Context", "{\"departmentId\":\"a\"}");
        when(service.execute(anyString(), anyString(), anyString(), anyString(), any(), any()))
            .thenAnswer(call -> ((Supplier<?>) call.getArgument(4)).get());
        var controller = controller();
        Row first = new Row(); first.title = "reviewed";
        RecordSaveRequestSupport.execute(controller, "create", null, first, () -> { first.setId("server-id"); return first; });
        Row retry = new Row(); retry.title = "reviewed";
        RecordSaveRequestSupport.execute(controller, "create", null, retry, () -> retry);
        request.removeHeader("X-MuYun-Page-Context");
        request.addHeader("X-MuYun-Page-Context", "{\"departmentId\":\"b\"}");
        RecordSaveRequestSupport.execute(controller, "create", null, retry, () -> retry);
        var fingerprint = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(service, times(3)).execute(anyString(), eq("demo.record"), eq("create"), fingerprint.capture(), any(), any());
        assertThat(fingerprint.getAllValues().get(0)).isEqualTo(fingerprint.getAllValues().get(1))
            .isNotEqualTo(fingerprint.getAllValues().get(2));
    }
    @Test void authorizesCurrentRecordAccessBeforeDisclosingReceipt() {
        var service = mock(RecordSaveReceiptService.class);
        bind(service);
        var receipt = new RecordSaveReceipt(); receipt.setRecordId("saved"); receipt.setRecordVersion(3);
        when(service.lookup("request", "demo.record")).thenReturn(receipt);
        var controller = controller();
        when(controller.view("saved")).thenThrow(new SecurityException("revoked"));
        assertThatThrownBy(() -> RecordSaveRequestSupport.lookup(controller, "request")).isInstanceOf(SecurityException.class);
    }
    static class Row extends StandardEntity { public String title; }
}
