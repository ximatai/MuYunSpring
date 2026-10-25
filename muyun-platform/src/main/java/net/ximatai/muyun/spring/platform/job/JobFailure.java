package net.ximatai.muyun.spring.platform.job;

/** Only the stable code is persisted; exception messages and causes never enter the run history. */
public class JobFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;
    public JobFailure(String code, boolean retryable) {
        super(code);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid failure code");
        this.code = code; this.retryable = retryable;
    }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
