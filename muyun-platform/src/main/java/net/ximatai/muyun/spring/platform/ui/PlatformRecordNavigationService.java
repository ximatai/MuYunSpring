package net.ximatai.muyun.spring.platform.ui;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Service
public class PlatformRecordNavigationService extends AbstractAbilityService<PlatformRecordNavigationSession> {
    public static final String MODULE_ALIAS = "platform.record_navigation_session";

    public PlatformRecordNavigationService(BaseDao<PlatformRecordNavigationSession, String> sessionDao) {
        super(MODULE_ALIAS, PlatformRecordNavigationSession.class, sessionDao);
    }

    @Transactional
    public PlatformRecordNavigationContext createCurrentUserSession(String moduleAlias,
                                                                    String entityAlias,
                                                                    List<String> recordIds,
                                                                    int pageNum,
                                                                    int pageSize,
                                                                    long total) {
        return createCurrentUserSession(moduleAlias, entityAlias, recordIds, pageNum, pageSize, total, null);
    }

    @Transactional
    public PlatformRecordNavigationContext createCurrentUserSession(String moduleAlias,
                                                                    String entityAlias,
                                                                    List<String> recordIds,
                                                                    int pageNum,
                                                                    int pageSize,
                                                                    long total,
                                                                    String querySnapshotKey) {
        List<String> normalizedIds = normalizeRecordIds(recordIds);
        if (normalizedIds.isEmpty()) {
            return null;
        }
        PlatformRecordNavigationSession session = new PlatformRecordNavigationSession();
        session.setUserId(currentUserId());
        session.setModuleAlias(PlatformNameRules.requireModuleAlias(moduleAlias));
        session.setEntityAlias(PlatformNameRules.requireIdentifier(entityAlias, "entityAlias"));
        session.setRecordIds(String.join(",", normalizedIds));
        session.setPageNum(pageNum);
        session.setPageSize(pageSize);
        session.setTotal(total);
        session.setQuerySnapshotKey(normalizeOptionalText(querySnapshotKey));
        insert(session);
        return context(session);
    }

    public PlatformRecordNavigationMove move(String moduleAlias, String sessionId, String currentRecordId) {
        PlatformRecordNavigationSession session = select(requireText(sessionId, "sessionId"));
        if (session == null
                || !Objects.equals(session.getUserId(), currentUserId())
                || !Objects.equals(session.getModuleAlias(), PlatformNameRules.requireModuleAlias(moduleAlias))) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-record-navigation.record-navigation-session-is-not-available",
                    "record navigation session is not available: {value1}"), Map.of("value1", String.valueOf(sessionId)));
        }
        List<String> ids = recordIds(session);
        String normalizedCurrent = requireText(currentRecordId, "currentRecordId");
        int index = ids.indexOf(normalizedCurrent);
        if (index < 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-record-navigation.record-is-not-in-navigation-session",
                    "record is not in navigation session: {value1}"), Map.of("value1", String.valueOf(currentRecordId)));
        }
        String previous = index == 0 ? null : ids.get(index - 1);
        String next = index >= ids.size() - 1 ? null : ids.get(index + 1);
        return new PlatformRecordNavigationMove(session.getId(), normalizedCurrent, previous, next,
                previous == null, next == null);
    }

    private PlatformRecordNavigationContext context(PlatformRecordNavigationSession session) {
        return new PlatformRecordNavigationContext(
                session.getId(),
                session.getModuleAlias(),
                session.getEntityAlias(),
                recordIds(session),
                session.getPageNum() == null ? 1 : session.getPageNum(),
                session.getPageSize() == null ? 0 : session.getPageSize(),
                session.getTotal() == null ? -1 : session.getTotal(),
                session.getQuerySnapshotKey()
        );
    }

    private List<String> recordIds(PlatformRecordNavigationSession session) {
        if (session == null || session.getRecordIds() == null || session.getRecordIds().isBlank()) {
            return List.of();
        }
        return Arrays.stream(session.getRecordIds().split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private List<String> normalizeRecordIds(List<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            return List.of();
        }
        return recordIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String currentUserId() {
        CurrentUser user = CurrentUserContext.currentUser()
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.platform-record-navigation.record-navigation-requires-current-user",
                        "record navigation requires current user")));
        if (user.system()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-record-navigation.record-navigation-requires-tenant-user",
                    "record navigation requires tenant user"));
        }
        return user.userId();
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-record-navigation.record-navigation-must-not-be-blank",
                    "record navigation {value1} must not be blank"), Map.of("value1", String.valueOf(fieldName)));
        }
        return value.trim();
    }

    private String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
