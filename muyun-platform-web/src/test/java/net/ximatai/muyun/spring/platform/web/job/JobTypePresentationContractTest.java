package net.ximatai.muyun.spring.platform.web.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.TextResolver;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.platform.job.*;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.platform.web.RecordTextPresentationService;
import net.ximatai.muyun.spring.web.WebListResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobTypePresentationContractTest {
    @Test
    void registeredTypeNamesUseTranslatedPresentationWithoutChangingExecutionIdentity() {
        var definition = new JobDefinition("platform.exchange-retention", "platform.job.type.exchangeRetention",
                JobScope.PLATFORM, null, "default", Duration.ofMinutes(1), Duration.ofMinutes(10), 1,
                Duration.ZERO, input -> "default", true, 3, true, null);
        var handler = mock(JobHandler.class);
        when(handler.definition()).thenReturn(definition);
        var registry = new JobRegistry(List.of(handler));
        var service = spy(new JobRunManagementService(mock(JobRunDao.class), tenant -> {}, registry));
        var run = new JobRun(); run.setId("run"); run.setJobType(definition.code());
        doReturn(List.of(run)).when(service).list(any(Criteria.class), any(PageRequest.class));
        var source = service.recordTextSources(run).get("jobType");
        assertThat(source.sourceId()).isEqualTo(definition.nameKey());

        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("abilities", new StaticAbilityCatalog(List.of(service)));
        beans.registerSingleton("languages", LanguageCatalog.load(getClass().getClassLoader(), "zh-CN"));
        beans.registerSingleton("texts", (TextResolver) (text, arguments, language) -> new TextResources.Resolution(
                "en".equals(language) ? "Exchange file cleanup" : "交换文件清理", language, language, false, false));
        var mapper = new ObjectMapper();
        var texts = new RecordTextPresentationService(mapper, beans.getBeanProvider(StaticAbilityCatalog.class),
                beans.getBeanProvider(TextResolver.class), beans.getBeanProvider(LanguageCatalog.class),
                beans.getBeanProvider(OptionSourceRegistry.class));
        var output = mapper.valueToTree(texts.project(service.getModuleAlias(), new WebListResponse<>(List.of(run))));
        assertThat(output.at("/records/0/jobType").asText()).isEqualTo(definition.code());
        assertThat(output.at("/records/0/$texts/jobType/languages/en/text").asText()).isEqualTo("Exchange file cleanup");
        assertThat(run.getJobType()).isEqualTo(definition.code());

        run.setJobType("removed.type");
        assertThat(service.recordTextSources(run)).isEmpty();
        assertThat(run.getJobType()).isEqualTo("removed.type");
    }
}
