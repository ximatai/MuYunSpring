package net.ximatai.muyun.spring.platform.ai;

/** Safe, content-free result of a privileged model connection test. */
public record AiModelConnectionTestResult(boolean connected, long durationMillis, String message) {

    public AiModelConnectionTestResult(boolean connected, long durationMillis) {
        this(connected, durationMillis, "模型连接验证通过");
    }
}
