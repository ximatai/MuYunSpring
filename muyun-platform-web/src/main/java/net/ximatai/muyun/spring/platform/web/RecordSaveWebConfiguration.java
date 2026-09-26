package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.platform.save.RecordSaveReceiptService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration(proxyBeanMethods = false)
public class RecordSaveWebConfiguration implements WebMvcConfigurer {
    private final org.springframework.beans.factory.ObjectProvider<RecordSaveReceiptService> receipts;
    private final ObjectMapper mapper;

    public RecordSaveWebConfiguration(org.springframework.beans.factory.ObjectProvider<RecordSaveReceiptService> receipts, ObjectMapper mapper) {
        this.receipts = receipts;
        this.mapper = mapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                var service = receipts.getIfAvailable();
                if (service != null) request.setAttribute(RecordSaveRequestSupport.ATTRIBUTE,
                        new RecordSaveRequestSupport(service, mapper));
                return true;
            }
        });
    }
}
