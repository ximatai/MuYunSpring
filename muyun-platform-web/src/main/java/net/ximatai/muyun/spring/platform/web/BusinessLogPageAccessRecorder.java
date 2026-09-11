package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogDetails;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogEvent;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.platform.ui.PlatformPageBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/** Emits successful, permission-scoped menu page entries without making page delivery depend on storage. */
public final class BusinessLogPageAccessRecorder {
    private static final Logger log = LoggerFactory.getLogger(BusinessLogPageAccessRecorder.class);
    private static final BusinessLogPageAccessRecorder NOOP = new BusinessLogPageAccessRecorder(null);
    private final BusinessLogPublisher publisher;

    public BusinessLogPageAccessRecorder(BusinessLogPublisher publisher) {
        this.publisher = publisher;
    }

    public static BusinessLogPageAccessRecorder noop() {
        return NOOP;
    }

    public void recordSuccessfulMenuEntry(PlatformPageBootstrap bootstrap) {
        if (publisher == null || bootstrap == null || bootstrap.entry() == null) {
            return;
        }
        try {
            var entry = bootstrap.entry();
            var currentUser = CurrentUserContext.currentUser();
            String tenantId = TenantContext.currentTenantId()
                    .orElseGet(() -> currentUser.map(user -> user.tenantId()).orElse(null));
            String pageMode = entry.pageMode() == null ? "LIST" : entry.pageMode().name();
            String pageKey = entry.moduleAlias() + ":" + pageMode;
            BusinessLogContext context = BusinessLogContext.capturedNow(Ids.newId(), Instant.now(),
                    RequestTraceContext.currentTraceId().orElse(null), tenantId,
                    currentUser.map(user -> user.userId()).orElse(null), entry.moduleAlias(), "page-entry");
            publisher.publish(new PageAccessLogEvent(context,
                    new PageAccessLogDetails(pageKey, null, entry.menuId(), "MENU_BOOTSTRAP")));
        } catch (RuntimeException ignored) {
            log.warn("Page access log publication failed");
        }
    }
}
