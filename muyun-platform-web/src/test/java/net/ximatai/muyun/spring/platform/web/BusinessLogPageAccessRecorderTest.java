package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.BusinessLogWriteResult;
import net.ximatai.muyun.spring.platform.menu.MenuPageMode;
import net.ximatai.muyun.spring.platform.ui.PlatformPageBootstrap;
import net.ximatai.muyun.spring.platform.ui.PlatformPageEntryContext;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedPageConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

class BusinessLogPageAccessRecorderTest {
    @Test
    void shouldNotBlockSuccessfulPageDeliveryWhenPublicationFails() {
        BusinessLogPublisher failingPublisher = new BusinessLogPublisher() {
            @Override public BusinessLogWriteResult publish(BusinessLogEvent event) { throw new IllegalStateException("down"); }
            @Override public List<BusinessLogWriteResult> publishAll(Collection<? extends BusinessLogEvent> events) { throw new IllegalStateException("down"); }
        };
        PlatformPageBootstrap bootstrap = new PlatformPageBootstrap(
                new PlatformPageEntryContext("menu-1", "sales.contract", MenuPageMode.LIST, null, null, null),
                PlatformUiClientType.WEB, PlatformResolvedPageConfig.empty());

        assertThatCode(() -> new BusinessLogPageAccessRecorder(failingPublisher).recordSuccessfulMenuEntry(bootstrap))
                .doesNotThrowAnyException();
    }
}
