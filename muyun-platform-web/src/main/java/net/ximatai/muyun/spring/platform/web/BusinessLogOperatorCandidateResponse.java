package net.ximatai.muyun.spring.platform.web;

/** One selectable log operator. {@code id} is the user-account ID recorded on the event. */
public record BusinessLogOperatorCandidateResponse(String id, String title, String subtitle) {
}
