package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.web.PlatformWebPathRules;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Opt-in form execution; the adapter never persists a draft or delegates to a list action. */
public interface FormActionWeb<S, T, R> extends ScopedWeb<S> {
    R executeFormAction(String actionCode, FormActionRequest<T> request);

    @PostMapping("/form-actions/" + PlatformWebPathRules.ACTION_CODE_PATH)
    default R formAction(@PathVariable String actionCode, @RequestBody FormActionRequest<T> request) {
        return webScope(() -> executeFormAction(actionCode, request));
    }
}
