package net.ximatai.muyun.spring.common.platform;

import java.util.Map;

/** Read-only business facts used by matching, participants and completion checks. */
public interface ModuleRecordFacts {
    Map<String, Object> read(String moduleAlias, String recordId);
    /** Permission checking belongs to the caller. Opt in only after applying LIST field-output protection; raw facts are never a presentation fallback. */
    default String displayTitle(String moduleAlias, String recordId) {
        return null;
    }
}
