package net.ximatai.muyun.spring.common.identity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IdentityIsolationTest {
    @Test void scopedAbsenceRestoresNestedIdentityAndPrincipalEvenOnFailure() {
        var user = CurrentUser.tenantUser("reader", "Reader", "tenant");
        var acting = new ActingContext("grant", user, BusinessPrincipal.employee("principal", "org", null), "quality.issue", "view");
        try (var actor = CurrentUserContext.use(user); var principal = ActingContextHolder.use(acting)) {
            assertThatThrownBy(() -> {
                try (var noActor = CurrentUserContext.without(); var noPrincipal = ActingContextHolder.without()) {
                    assertThat(CurrentUserContext.currentUser()).isEmpty();
                    assertThat(ActingContextHolder.current()).isEmpty();
                    try (var nested = CurrentUserContext.use(user)) { assertThat(CurrentUserContext.currentUser()).contains(user); }
                    assertThat(CurrentUserContext.currentUser()).isEmpty();
                    throw new IllegalStateException("failed");
                }
            }).hasMessage("failed");
            assertThat(CurrentUserContext.currentUser()).contains(user);
            assertThat(ActingContextHolder.current()).contains(acting);
        }
        assertThat(CurrentUserContext.currentUser()).isEmpty();
        assertThat(ActingContextHolder.current()).isEmpty();
    }
}
