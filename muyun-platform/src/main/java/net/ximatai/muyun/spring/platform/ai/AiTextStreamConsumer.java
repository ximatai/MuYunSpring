package net.ximatai.muyun.spring.platform.ai;

@FunctionalInterface
public interface AiTextStreamConsumer {
    void accept(String delta);
}
