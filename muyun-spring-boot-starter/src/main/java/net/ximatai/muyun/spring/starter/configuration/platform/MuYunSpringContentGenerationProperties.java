package net.ximatai.muyun.spring.starter.configuration.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import java.time.Duration;

/** Compatibility mapping: existing content settings configure the platform execution channel. */
@ConfigurationProperties("muyun.content")
public class MuYunSpringContentGenerationProperties {
    private int generationWorkers = 1;
    private long generationDelayMs = 1000;
    private long generationInitialDelayMs = 60000;
    public int getGenerationWorkers() { return generationWorkers; }
    public void setGenerationWorkers(int value) { generationWorkers = value; }
    public long getGenerationDelayMs() { return generationDelayMs; }
    public void setGenerationDelayMs(long value) { generationDelayMs = value; }
    public long getGenerationInitialDelayMs() { return generationInitialDelayMs; }
    public void setGenerationInitialDelayMs(long value) { generationInitialDelayMs = value; }

    void configure(MuYunSpringJobProperties jobs, Environment environment) {
        if (generationWorkers < 1 || generationWorkers > 8)
            throw new IllegalArgumentException("Content generation workers must be between 1 and 8");
        if (generationInitialDelayMs < 0 || generationInitialDelayMs > 300000 || generationDelayMs < 100 || generationDelayMs > 60000)
            throw new IllegalArgumentException("Invalid content generation scan interval");
        var channel = jobs.getChannels().computeIfAbsent("content-generation", ignored -> new MuYunSpringJobProperties.Channel());
        String prefix = "muyun.platform.job.channels.content-generation.";
        if (!environment.containsProperty(prefix + "threads")) channel.setThreads(generationWorkers);
        else if (environment.containsProperty("muyun.content.generation-workers") && channel.getThreads() != generationWorkers)
            throw new IllegalArgumentException("Conflicting content generation worker settings");
        if (!environment.containsProperty(prefix + "claim-interval")) channel.setClaimInterval(Duration.ofMillis(generationDelayMs));
        else if (environment.containsProperty("muyun.content.generation-delay-ms") && !channel.getClaimInterval().equals(Duration.ofMillis(generationDelayMs)))
            throw new IllegalArgumentException("Conflicting content generation interval settings");
        if (channel.getThreads() < 1 || channel.getThreads() > 8)
            throw new IllegalArgumentException("Content generation workers must be between 1 and 8");
        channel.setInitialDelay(Duration.ofMillis(generationInitialDelayMs));
        channel.setDelayAfterExecution(true);
    }
}
