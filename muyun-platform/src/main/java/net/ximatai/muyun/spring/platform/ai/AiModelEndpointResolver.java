package net.ximatai.muyun.spring.platform.ai;

/** Keeps provider endpoint admission separate from tenant-owned model credentials. */
@FunctionalInterface
public interface AiModelEndpointResolver {
    String resolve(AiModelConfiguration configuration);
}
