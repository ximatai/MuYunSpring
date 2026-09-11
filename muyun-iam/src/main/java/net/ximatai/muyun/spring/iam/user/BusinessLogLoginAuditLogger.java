package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.LoginLogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** Bridges IAM login facts to the storage-neutral publisher with a fail-open policy. */
public class BusinessLogLoginAuditLogger implements LoginAuditLogger {
    private static final Logger log = LoggerFactory.getLogger(BusinessLogLoginAuditLogger.class);
    private final BusinessLogPublisher publisher;

    public BusinessLogLoginAuditLogger(BusinessLogPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    @Override
    public void record(LoginLogEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException ignored) {
            // Never include the event, request values or exception: any of them may contain credentials.
            log.warn("Login audit publication failed");
        }
    }
}
