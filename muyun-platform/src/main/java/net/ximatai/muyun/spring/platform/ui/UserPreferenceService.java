package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Service
public class UserPreferenceService extends AbstractAbilityService<UserPreference> {
    public static final String MODULE_ALIAS = "platform.user_preference";
    public static final int MAX_VALUE_JSON_BYTES = 64 * 1024;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public UserPreferenceService(BaseDao<UserPreference, String> preferenceDao) {
        super(MODULE_ALIAS, UserPreference.class, preferenceDao);
    }

    public UserPreference currentUserPreference(PlatformUiClientType clientType, String preferenceKey) {
        PreferenceScope scope = currentScope(clientType, preferenceKey);
        return select(scope.id());
    }

    @Transactional
    public UserPreference saveCurrentUserPreference(PlatformUiClientType clientType,
                                                     String preferenceKey,
                                                     String valueJson) {
        PreferenceScope scope = currentScope(clientType, preferenceKey);
        String normalizedValue = requireValueJson(valueJson);
        UserPreference preference = getDao().findById(scope.id());
        Instant now = Instant.now();
        if (preference == null) {
            preference = new UserPreference();
            preference.setId(scope.id());
            preference.setTenantId(scope.tenantId());
            preference.setUserId(scope.userId());
            preference.setClientType(scope.clientType().name());
            preference.setPreferenceKey(scope.preferenceKey());
            preference.setValueJson(normalizedValue);
            EntityLifecycle.prepareInsert(preference, now);
        } else {
            preference.setValueJson(normalizedValue);
            EntityLifecycle.prepareUpdate(preference, now);
        }
        getDao().upsert(preference);
        return select(scope.id());
    }

    @Transactional
    public void deleteCurrentUserPreference(PlatformUiClientType clientType, String preferenceKey) {
        delete(currentScope(clientType, preferenceKey).id());
    }

    /** Stable scope ids turn the generic DAO's primary-key upsert into an atomic last-write-wins save. */
    private PreferenceScope currentScope(PlatformUiClientType clientType, String preferenceKey) {
        CurrentUser user = CurrentUserContext.currentUser()
                .orElseThrow(() -> new PlatformException("user preference requires current user"));
        PlatformUiClientType normalizedClientType = normalizeClientType(clientType);
        String normalizedPreferenceKey = normalizePreferenceKey(preferenceKey);
        String tenantId = currentTenantId(user);
        String identity = String.join("\u0000",
                user.system() ? "system" : "tenant",
                tenantId == null ? "" : tenantId,
                user.userId(),
                normalizedClientType.name(),
                normalizedPreferenceKey);
        String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
        return new PreferenceScope(id, tenantId, user.userId(), normalizedClientType, normalizedPreferenceKey);
    }

    private String currentTenantId(CurrentUser user) {
        if (user.system()) {
            return null;
        }
        if (user.tenantId() == null) {
            throw new PlatformException("user preference requires tenant id for tenant user");
        }
        return user.tenantId();
    }

    private PlatformUiClientType normalizeClientType(PlatformUiClientType clientType) {
        return clientType == null ? PlatformUiClientType.WEB : clientType;
    }

    private String normalizePreferenceKey(String preferenceKey) {
        String normalized = requireText(preferenceKey, "preferenceKey");
        if (normalized.length() > 128) {
            throw new PlatformException("user preference preferenceKey must not exceed 128 characters");
        }
        if (!normalized.matches("[a-z][a-z0-9_]*(?:[.-][a-z][a-z0-9_]*)*")) {
            throw new PlatformException("user preference preferenceKey must use lower-case dotted or dashed segments");
        }
        return normalized;
    }

    private String requireValueJson(String valueJson) {
        String normalized = requireText(valueJson, "valueJson");
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_JSON_BYTES) {
            throw new PlatformException("user preference valueJson must not exceed "
                    + MAX_VALUE_JSON_BYTES + " UTF-8 bytes");
        }
        try {
            OBJECT_MAPPER.readTree(normalized);
            return normalized;
        } catch (JsonProcessingException ex) {
            throw new PlatformException("user preference valueJson must be valid JSON", ex);
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformException("user preference " + fieldName + " must not be blank");
        }
        return value.trim();
    }

    private record PreferenceScope(String id,
                                   String tenantId,
                                   String userId,
                                   PlatformUiClientType clientType,
                                   String preferenceKey) {
    }
}
