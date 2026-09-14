package net.ximatai.muyun.spring.platform.web;

/**
 * One selectable log operator. {@code id} is the user-account ID recorded on the event.
 *
 * <p>{@code organizationId}/{@code departmentId} preserve the authorized event-time attribution.
 * Their names are supplied only when the current identity still resolves to that same assignment.</p>
 */
public record BusinessLogOperatorCandidateResponse(
        String id,
        String title,
        String account,
        String employeeName,
        String organizationId,
        String organizationName,
        String departmentId,
        String departmentName
) {
}
