package net.ximatai.muyun.spring.ability.permission;

import java.util.List;
import java.util.Map;

public record RecordPermissionState(Integer version, String ownerId, List<String> assigneeIds,
        List<String> memberIds, Map<String, String> titles) { }
