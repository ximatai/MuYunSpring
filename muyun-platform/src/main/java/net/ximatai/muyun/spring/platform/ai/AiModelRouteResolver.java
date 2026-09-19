package net.ximatai.muyun.spring.platform.ai;

/** Resolves management records into a transport-ready route without exposing persistence entities to adapters. */
interface AiModelRouteResolver {
    ResolvedAiModelRoute resolveCurrent();

    ResolvedAiModelRoute resolveCandidate(AiModelConfiguration configuration);
}
