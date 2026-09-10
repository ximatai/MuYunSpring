package net.ximatai.muyun.spring.platform.web;

public record RelationProjectionPlanningOptions(int maxJoinDepth, int maxJoinCount) {
    public static final int DEFAULT_MAX_JOIN_DEPTH = net.ximatai.muyun.spring.ability.reference.ReferencePathPlanningOptions.DEFAULT_MAX_HOPS;
    public static final int DEFAULT_MAX_JOIN_COUNT = 24;
    public static final RelationProjectionPlanningOptions DEFAULT =
            new RelationProjectionPlanningOptions(DEFAULT_MAX_JOIN_DEPTH, DEFAULT_MAX_JOIN_COUNT);

    public RelationProjectionPlanningOptions {
        if (maxJoinDepth <= 0) {
            throw new IllegalArgumentException("relation projection max join depth must be positive");
        }
        if (maxJoinCount <= 0) {
            throw new IllegalArgumentException("relation projection max join count must be positive");
        }
    }

    public static RelationProjectionPlanningOptions defaults() {
        return DEFAULT;
    }
}
