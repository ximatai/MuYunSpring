package net.ximatai.muyun.spring.web.realtime;

import net.ximatai.muyun.spring.ability.action.CommittedChangeSet;
import net.ximatai.muyun.spring.ability.action.DataChange;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StompDataChangeRealtimePublisher implements DataChangeRealtimePublisher {
    public static final String MESSAGE_TYPE = "platform.data-change";

    private final RealtimeMessagePublisher messagePublisher;

    public StompDataChangeRealtimePublisher(RealtimeMessagePublisher messagePublisher) {
        this.messagePublisher = messagePublisher;
    }

    @Override
    public void publish(CommittedChangeSet changeSet) {
        if (changeSet == null || changeSet.changes().isEmpty()) {
            return;
        }
        CurrentUser currentUser = CurrentUserContext.currentUser().orElse(null);
        if (currentUser == null) {
            return;
        }
        String traceId = RequestTraceContext.currentTraceId().orElse(null);
        RealtimeEnvelope<CommittedChangeSet> envelope = RealtimeEnvelope.of(MESSAGE_TYPE, traceId, changeSet);
        messagePublisher.sendToUser(currentUser.userId(), RealtimeDestinations.DATA_CHANGES, envelope);
        broadcastChangeSummaries(changeSet, traceId);
    }

    private void broadcastChangeSummaries(CommittedChangeSet changeSet, String traceId) {
        for (Map.Entry<String, List<DataChange>> entry : moduleSummaries(changeSet).entrySet()) {
            messagePublisher.broadcast(RealtimeDestinations.moduleDataChanges(entry.getKey()),
                    summaryEnvelope(changeSet.changeSetId(), entry.getValue(), traceId));
        }
        for (Map.Entry<RecordTopicKey, List<DataChange>> entry : recordSummaries(changeSet).entrySet()) {
            RecordTopicKey key = entry.getKey();
            messagePublisher.broadcast(RealtimeDestinations.recordDataChanges(key.moduleAlias(), key.recordId()),
                    summaryEnvelope(changeSet.changeSetId(), entry.getValue(), traceId));
        }
        for (Map.Entry<ResourceTopicKey, List<DataChange>> entry : resourceSummaries(changeSet).entrySet()) {
            ResourceTopicKey key = entry.getKey();
            messagePublisher.broadcast(RealtimeDestinations.resourceDataChanges(key.moduleAlias(), key.resourceKey()),
                    summaryEnvelope(changeSet.changeSetId(), entry.getValue(), traceId));
        }
        for (Map.Entry<ResourceRecordTopicKey, List<DataChange>> entry : resourceRecordSummaries(changeSet).entrySet()) {
            ResourceRecordTopicKey key = entry.getKey();
            messagePublisher.broadcast(RealtimeDestinations.resourceRecordDataChanges(
                            key.moduleAlias(), key.resourceKey(), key.recordId()),
                    summaryEnvelope(changeSet.changeSetId(), entry.getValue(), traceId));
        }
    }

    private RealtimeEnvelope<CommittedChangeSet> summaryEnvelope(String changeSetId, List<DataChange> changes,
                                                                 String traceId) {
        return RealtimeEnvelope.of(MESSAGE_TYPE, traceId, new CommittedChangeSet(changeSetId, changes));
    }

    private Map<String, List<DataChange>> moduleSummaries(CommittedChangeSet changeSet) {
        Map<String, List<DataChange>> summaries = new LinkedHashMap<>();
        for (DataChange change : changeSet.changes()) {
            if (change == null) {
                continue;
            }
            summaries.computeIfAbsent(change.moduleAlias(), ignored -> new ArrayList<>()).add(summary(change));
        }
        return summaries;
    }

    private Map<RecordTopicKey, List<DataChange>> recordSummaries(CommittedChangeSet changeSet) {
        Map<RecordTopicKey, List<DataChange>> summaries = new LinkedHashMap<>();
        for (DataChange change : changeSet.changes()) {
            if (change == null) {
                continue;
            }
            String rootRecordId = hasResource(change) ? change.scope() : change.recordId();
            if (rootRecordId == null || rootRecordId.isBlank()) {
                continue;
            }
            summaries.computeIfAbsent(new RecordTopicKey(change.moduleAlias(), rootRecordId),
                    ignored -> new ArrayList<>()).add(summary(change));
        }
        return summaries;
    }

    private Map<ResourceTopicKey, List<DataChange>> resourceSummaries(CommittedChangeSet changeSet) {
        Map<ResourceTopicKey, List<DataChange>> summaries = new LinkedHashMap<>();
        for (DataChange change : changeSet.changes()) {
            if (!hasResource(change)) continue;
            summaries.computeIfAbsent(new ResourceTopicKey(change.moduleAlias(), change.resourceKey()),
                    ignored -> new ArrayList<>()).add(summary(change));
        }
        return summaries;
    }

    private Map<ResourceRecordTopicKey, List<DataChange>> resourceRecordSummaries(CommittedChangeSet changeSet) {
        Map<ResourceRecordTopicKey, List<DataChange>> summaries = new LinkedHashMap<>();
        for (DataChange change : changeSet.changes()) {
            if (!hasResource(change) || change.recordId() == null || change.recordId().isBlank()) continue;
            summaries.computeIfAbsent(new ResourceRecordTopicKey(
                    change.moduleAlias(), change.resourceKey(), change.recordId()),
                    ignored -> new ArrayList<>()).add(summary(change));
        }
        return summaries;
    }

    private boolean hasResource(DataChange change) {
        return change != null && change.resourceKey() != null && !change.resourceKey().isBlank();
    }

    private DataChange summary(DataChange change) {
        return new DataChange(change.type(), change.moduleAlias(), change.recordId(), change.resourceKey(),
                change.scope(), Map.of());
    }

    private record RecordTopicKey(String moduleAlias, String recordId) {
    }

    private record ResourceTopicKey(String moduleAlias, String resourceKey) {
    }

    private record ResourceRecordTopicKey(String moduleAlias, String resourceKey, String recordId) {
    }
}
