package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.UserPreference;
import net.ximatai.muyun.spring.platform.ui.UserPreferenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/platform.user-preference/{preferenceKey:.+}")
public class UserPreferenceWebController {
    private final UserPreferenceService preferenceService;

    public UserPreferenceWebController(UserPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping
    public ResponseEntity<UserPreferenceResponse> preference(@PathVariable String preferenceKey,
                                                              @RequestParam(defaultValue = "WEB") PlatformUiClientType clientType) {
        UserPreference preference = preferenceService.currentUserPreference(clientType, preferenceKey);
        return preference == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(toResponse(preference));
    }

    @PostMapping
    public UserPreferenceResponse savePreference(@PathVariable String preferenceKey,
                                                 @RequestBody UserPreferenceRequest request) {
        return toResponse(preferenceService.saveCurrentUserPreference(
                request.clientType(), preferenceKey, request.valueJson()));
    }

    @DeleteMapping
    public ResponseEntity<Void> deletePreference(@PathVariable String preferenceKey,
                                                  @RequestParam(defaultValue = "WEB") PlatformUiClientType clientType) {
        preferenceService.deleteCurrentUserPreference(clientType, preferenceKey);
        return ResponseEntity.noContent().build();
    }

    private UserPreferenceResponse toResponse(UserPreference preference) {
        return new UserPreferenceResponse(preference.getValueJson());
    }
}

record UserPreferenceRequest(PlatformUiClientType clientType, String valueJson) {
}

record UserPreferenceResponse(String valueJson) {
}
