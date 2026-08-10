package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPreferenceService extends AbstractAbilityService<UserPreference> {
    public static final String MODULE_ALIAS = "platform.user_preference";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public UserPreferenceService(BaseDao<UserPreference, String> preferenceDao) {
        super(MODULE_ALIAS, UserPreference.class, preferenceDao);
    }

    public UserPreference currentUserPreference(PlatformUiClientType clientType, String preferenceKey) {
        return findOne(scopeCriteria(currentUserId(), clientType, preferenceKey));
    }

    @Transactional
    public UserPreference saveCurrentUserPreference(PlatformUiClientType clientType,
                                                     String preferenceKey,
                                                     String valueJson) {
        String userId = currentUserId();
        Criteria scope = scopeCriteria(userId, clientType, preferenceKey);
        UserPreference preference = findOne(scope);
        if (preference == null) {
            preference = new UserPreference();
            preference.setUserId(userId);
            preference.setClientType(normalizeClientType(clientType).name());
            preference.setPreferenceKey(normalizePreferenceKey(preferenceKey));
            preference.setValueJson(requireValueJson(valueJson));
            insert(preference);
            return preference;
        }
        preference.setValueJson(requireValueJson(valueJson));
        update(preference);
        return select(preference.getId());
    }

    @Transactional
    public void deleteCurrentUserPreference(PlatformUiClientType clientType, String preferenceKey) {
        UserPreference preference = currentUserPreference(clientType, preferenceKey);
        if (preference != null) {
            delete(preference);
        }
    }

    private Criteria scopeCriteria(String userId, PlatformUiClientType clientType, String preferenceKey) {
        return Criteria.of()
                .eq("userId", userId)
                .eq("clientType", normalizeClientType(clientType).name())
                .eq("preferenceKey", normalizePreferenceKey(preferenceKey));
    }

    private String currentUserId() {
        CurrentUser user = CurrentUserContext.currentUser()
                .orElseThrow(() -> new PlatformException("user preference requires current user"));
        return user.userId();
    }

    private PlatformUiClientType normalizeClientType(PlatformUiClientType clientType) {
        return clientType == null ? PlatformUiClientType.WEB : clientType;
    }

    private String normalizePreferenceKey(String preferenceKey) {
        String normalized = requireText(preferenceKey, "preferenceKey");
        if (!normalized.matches("[a-z][a-z0-9_]*(?:[.-][a-z][a-z0-9_]*)*")) {
            throw new PlatformException("user preference preferenceKey must use lower-case dotted or dashed segments");
        }
        return normalized;
    }

    private String requireValueJson(String valueJson) {
        String normalized = requireText(valueJson, "valueJson");
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
}
