package net.ximatai.muyun.spring.platform.web.notification;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.TextResolver;
import net.ximatai.muyun.spring.common.i18n.TextPresentation;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;

import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.platform.notification.BusinessNotification;
import net.ximatai.muyun.spring.web.realtime.RealtimeDestinations;
import net.ximatai.muyun.spring.web.realtime.RealtimeEnvelope;
import net.ximatai.muyun.spring.web.realtime.RealtimeMessagePublisher;

public class StompBusinessNotificationNotifier implements BusinessNotificationNotifier {
    public static final String MESSAGE_TYPE = "platform.business-notification";

    private final RealtimeMessagePublisher messagePublisher;
    private final java.util.function.Supplier<TextResolver> resolvers;
    private final java.util.function.Supplier<LanguageCatalog> catalogs;

    public StompBusinessNotificationNotifier(RealtimeMessagePublisher messagePublisher,
            java.util.function.Supplier<TextResolver> resolvers,
            java.util.function.Supplier<LanguageCatalog> catalogs) {
        this.messagePublisher = java.util.Objects.requireNonNull(messagePublisher, "messagePublisher");
        this.resolvers = java.util.Objects.requireNonNull(resolvers, "resolvers");
        this.catalogs = java.util.Objects.requireNonNull(catalogs, "catalogs");
    }

    @Override
    public void notifyUser(String userId, BusinessNotification notification) {
        if (userId == null || userId.isBlank() || notification == null) return;
        var body = WebBusinessNotification.from(notification);
        var resolver = java.util.Objects.requireNonNull(resolvers.get(), "textResolver");
        var catalog = java.util.Objects.requireNonNull(catalogs.get(), "languageCatalog");
        if (!notification.textSources().isEmpty()) {
            var sources = new ArrayList<>(notification.textSources().values());
            notification.messageArgs().values().stream().filter(TextReference.class::isInstance)
                    .map(TextReference.class::cast).forEach(sources::add);
            var prepared = resolver.prepare(sources);
            var resources = new LinkedHashMap<String, TextPresentation>();
            notification.textSources().forEach((slot, source) -> {
                var languages = new LinkedHashMap<String, TextResources.Resolution>();
                for (var language : catalog.languages()) if (language.enabled()) {
                    var arguments = new LinkedHashMap<String, Object>();
                    notification.messageArgs().forEach((key, value) -> arguments.put(key,
                            value instanceof TextReference text ? prepared.resolve(text, Map.of(), language.tag()).text() : value));
                    languages.put(language.tag(), prepared.resolve(source, arguments, language.tag()));
                }
                resources.put(slot, new TextPresentation(source, languages));
            });
            body = body.withTextResources(resources);
        }
        messagePublisher.sendToUser(userId, RealtimeDestinations.USER_BUSINESS_NOTIFICATIONS,
                RealtimeEnvelope.of(MESSAGE_TYPE, RequestTraceContext.currentTraceId().orElse(null),
                        body));
    }
}
