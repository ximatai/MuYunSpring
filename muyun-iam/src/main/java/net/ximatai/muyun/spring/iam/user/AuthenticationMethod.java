package net.ximatai.muyun.spring.iam.user;

import java.util.regex.Pattern;

/**
 * How a session was authenticated. Recorded on the session and in login audit so sessions issued by
 * different credentials stay distinguishable; it never changes what the session is authorized to do.
 */
public record AuthenticationMethod(String code) {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9._:-]{0,63}");

    public static final AuthenticationMethod PASSWORD = new AuthenticationMethod("password");

    public AuthenticationMethod {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("invalid authentication method code: " + code);
        }
    }

    public boolean isPassword() {
        return PASSWORD.equals(this);
    }
}
