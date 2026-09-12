package net.ximatai.muyun.spring.ability.logging;

import java.util.List;

public record BusinessLogReadPage(List<BusinessLogEvent> events, BusinessLogCursor nextCursor) {
    public BusinessLogReadPage { events = List.copyOf(events); }
}
