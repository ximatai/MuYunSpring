package net.ximatai.muyun.spring.platform.workflow;

/** A frozen node caption keeps its identity even when several nodes share the same original title. */
public record WorkflowNodeTitleView(String nodeKey, String title, boolean addedByAddSign) { }
