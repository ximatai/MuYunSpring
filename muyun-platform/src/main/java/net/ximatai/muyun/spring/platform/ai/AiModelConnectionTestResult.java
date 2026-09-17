package net.ximatai.muyun.spring.platform.ai;

/** Safe, content-free result of a privileged model connection test. */
public record AiModelConnectionTestResult(boolean connected, long durationMillis) {
}
