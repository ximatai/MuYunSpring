package net.ximatai.muyun.spring.iam.user;

import java.time.Instant;

public record UserSessionView(
        String id,
        String userId,
        String username,
        String tenantId,
        String organizationId,
        Instant issuedAt,
        Instant expiresAt,
        Instant maxExpiresAt,
        Instant lastSeenAt,
        Boolean passwordChangeRequired,
        String authenticationMethod,
        String loginIp,
        String loginUserAgent,
        String terminalType,
        String terminalTypeTitle,
        String platformType,
        String platformTypeTitle,
        boolean current,
        boolean present,
        String presenceStatus,
        String presenceStatusTitle,
        long connectionCount,
        Instant lastConnectedAt,
        Instant lastObservedAt
) {
    static UserSessionView from(UserSession session, boolean current) {
        return from(session, current, UserSessionPresence.absent(session == null ? null : session.getId()),
                Instant.now());
    }

    static UserSessionView from(UserSession session, boolean current, UserSessionPresence presence) {
        return from(session, current, presence, Instant.now());
    }

    static UserSessionView from(UserSession session, boolean current, UserSessionPresence presence, Instant now) {
        TerminalType terminalType = TerminalType.of(session.getLoginUserAgent());
        PlatformType platformType = PlatformType.of(session.getLoginUserAgent());
        UserSessionPresence sessionPresence = presence == null
                ? UserSessionPresence.absent(session.getId())
                : presence;
        PresenceStatus presenceStatus = PresenceStatus.of(sessionPresence, session.getLastSeenAt(), now);
        return new UserSessionView(
                session.getId(),
                session.getUserId(),
                session.getUsername(),
                session.getTenantId(),
                session.getOrganizationId(),
                session.getIssuedAt(),
                session.getExpiresAt(),
                session.getMaxExpiresAt(),
                session.getLastSeenAt(),
                session.getPasswordChangeRequired(),
                session.getAuthenticationMethod(),
                session.getLoginIp(),
                session.getLoginUserAgent(),
                terminalType.code,
                terminalType.title,
                platformType.code,
                platformType.title,
                current,
                sessionPresence.present(),
                presenceStatus.code,
                presenceStatus.title,
                sessionPresence.connectionCount(),
                sessionPresence.lastConnectedAt(),
                sessionPresence.lastObservedAt()
        );
    }

    private enum PresenceStatus {
        ONLINE("online", "使用中"),
        IDLE("idle", "闲置"),
        OFFLINE("offline", "离线");

        private final String code;
        private final String title;

        PresenceStatus(String code, String title) {
            this.code = code;
            this.title = title;
        }

        private static PresenceStatus of(UserSessionPresence presence, Instant lastSeenAt, Instant now) {
            if (presence == null || !presence.present() || presence.connectionCount() <= 0) {
                return OFFLINE;
            }
            return presence.idleSince(lastSeenAt, now) ? IDLE : ONLINE;
        }
    }

    private enum TerminalType {
        DESKTOP_WEB("desktopWeb", "Web 桌面端"),
        MOBILE_WEB("mobileWeb", "Web 移动端"),
        TABLET_WEB("tabletWeb", "Web 平板端"),
        DESKTOP_APP("desktopApp", "桌面客户端"),
        MOBILE_APP("mobileApp", "移动客户端"),
        OTHER("other", "其他终端");

        private final String code;
        private final String title;

        TerminalType(String code, String title) {
            this.code = code;
            this.title = title;
        }

        private static TerminalType of(String userAgent) {
            if (userAgent == null || userAgent.isBlank()) {
                return OTHER;
            }
            String normalized = userAgent.toLowerCase();
            if (normalized.contains("electron") || normalized.contains("tauri")) {
                return DESKTOP_APP;
            }
            if (normalized.contains("ipad") || normalized.contains("tablet")) {
                return normalized.contains("mozilla") ? TABLET_WEB : MOBILE_APP;
            }
            if (normalized.contains("android") && !normalized.contains("mobile")) {
                return normalized.contains("mozilla") ? TABLET_WEB : MOBILE_APP;
            }
            if (normalized.contains("mobile") || normalized.contains("iphone") || normalized.contains("ipod")) {
                return normalized.contains("mozilla") ? MOBILE_WEB : MOBILE_APP;
            }
            if (normalized.contains("okhttp") || normalized.contains("cfnetwork") || normalized.contains("dart")) {
                return MOBILE_APP;
            }
            if (normalized.contains("mozilla") || normalized.contains("chrome") || normalized.contains("safari")
                    || normalized.contains("firefox") || normalized.contains("edge")) {
                return DESKTOP_WEB;
            }
            return OTHER;
        }
    }

    private enum PlatformType {
        WINDOWS("windows", "Windows"),
        MACOS("macos", "macOS"),
        IOS("ios", "iOS"),
        ANDROID("android", "Android"),
        LINUX("linux", "Linux"),
        OTHER("other", "其他系统");

        private final String code;
        private final String title;

        PlatformType(String code, String title) {
            this.code = code;
            this.title = title;
        }

        private static PlatformType of(String userAgent) {
            if (userAgent == null || userAgent.isBlank()) {
                return OTHER;
            }
            String normalized = userAgent.toLowerCase();
            if (normalized.contains("iphone") || normalized.contains("ipad") || normalized.contains("ipod")) {
                return IOS;
            }
            if (normalized.contains("android")) {
                return ANDROID;
            }
            if (normalized.contains("windows")) {
                return WINDOWS;
            }
            if (normalized.contains("mac os x") || normalized.contains("macintosh")) {
                return MACOS;
            }
            if (normalized.contains("linux") || normalized.contains("x11")) {
                return LINUX;
            }
            return OTHER;
        }
    }
}
