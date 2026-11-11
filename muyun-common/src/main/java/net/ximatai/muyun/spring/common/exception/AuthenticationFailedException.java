package net.ximatai.muyun.spring.common.exception;

public class AuthenticationFailedException extends PlatformException {
    /** Structured, explicitly public guidance; never include internal authentication or audit facts. */
    public AuthenticationFailedException(String message, Throwable cause, java.util.Map<String, Object> details) {
        super(PlatformErrorCodes.LOGIN_BAD_CREDENTIALS, 401, message, cause,
                ErrorScope.empty(), java.util.List.of(), details);
    }

    public AuthenticationFailedException(String message) {
        super(PlatformErrorCodes.LOGIN_BAD_CREDENTIALS, 401, message);
    }

    public AuthenticationFailedException(String message, Throwable cause) {
        super(PlatformErrorCodes.LOGIN_BAD_CREDENTIALS, 401, message, cause);
    }
}
