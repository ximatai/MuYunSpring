package net.ximatai.muyun.spring.ability.logging;

import java.util.Collection;
import java.util.List;

/** Storage-neutral append and read boundary for business logging facts. */
public interface BusinessLogStore {
    BusinessLogWriteResult append(BusinessLogEvent event);

    List<BusinessLogWriteResult> appendAll(Collection<? extends BusinessLogEvent> events);

    BusinessLogReadPage read(BusinessLogQuery query);
}
