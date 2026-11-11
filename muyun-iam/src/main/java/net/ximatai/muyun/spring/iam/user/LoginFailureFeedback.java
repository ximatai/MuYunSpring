package net.ximatai.muyun.spring.iam.user;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Public password-login guidance in {@code LOGIN_BAD_CREDENTIALS.details.loginFailure}.
 * Only a current lock or the last two remaining attempts is disclosed; account identity,
 * credential validity and internal audit reasons are never included.
 * {@code serverTime} anchors countdowns independently of the client's wall clock.
 */
public record LoginFailureFeedback(@JsonFormat(shape = JsonFormat.Shape.STRING) Instant serverTime,
                                   @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lockedUntil,
                                   boolean lockTriggered, Integer remainingAttempts) {
}
