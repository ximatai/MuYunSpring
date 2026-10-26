package net.ximatai.muyun.spring.iam.welink;

import com.fasterxml.jackson.databind.JsonNode;
import net.ximatai.muyun.spring.iam.federation.*;
import java.util.*;
import java.time.Instant;
import java.time.Clock;

/** Read-only directory adapter. Unmapped employment statuses remain unknown. */
public final class WeLinkDirectoryProtocol implements ExternalDirectoryProtocol {
    private final WeLinkClient client;
    private final Clock clock;
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private record Snapshot(String authority, String root, List<Department> rows, Instant expiresAt) { }
    private static final int MAX_SNAPSHOT_ROWS = 100000;
    public WeLinkDirectoryProtocol(WeLinkClient client) { this(client, Clock.systemUTC()); }
    WeLinkDirectoryProtocol(WeLinkClient client, Clock clock) { this.client = client; this.clock = clock; }
    public String providerType() { return "welink"; }
    public void validateConfiguration(Map<String, String> configuration) { new WeLinkConfiguration(configuration); }
    public Set<String> identityScopeConfigurationKeys() { return WeLinkConfiguration.AUTHORITY_KEYS; }
    public Department department(IdentityProviderDescriptor provider, String id) {
        var result = client.directory(provider, "/api/contact/v2/department/detail", Map.of("deptCode", id));
        Department department = mapDepartment(result.path("data"), new WeLinkConfiguration(provider.configuration()));
        if (!id.equals(department.id())) throw WeLinkClient.unavailable();
        return department;
    }
    public Page<Department> departments(IdentityProviderDescriptor provider, String root, String cursor, int limit) {
        var config = new WeLinkConfiguration(provider.configuration());
        checkLimit(limit);
        if (cursor != null) return snapshotPage(provider, root, cursor, limit);
        // The vendor's cross-page ordering is unconfirmed. Read a bounded tree before exposing parent-first pages.
        var remaining = new LinkedHashMap<String, Department>();
        for (int page = 1; ; page++) {
            if (page > config.maxPages()) throw WeLinkClient.unavailable();
            var response = client.directory(provider, "/api/contact/v2/department/list", Map.of(
                    "deptCode", root, "recursiveflag", "1", "pageNo", Integer.toString(page),
                    "pageSize", Integer.toString(config.pageSize())));
            var data = rows(response);
            for (JsonNode row : data) {
                var department = mapDepartment(row, config);
                if (remaining.size() >= MAX_SNAPSHOT_ROWS) throw WeLinkClient.unavailable();
                if (remaining.putIfAbsent(department.id(), department) != null) throw WeLinkClient.unavailable();
            }
            if (finished(response, data.size(), config.pageSize(), config)) break;
        }
        var ordered = new ArrayList<Department>();
        var seen = new HashSet<String>();
        seen.add(root);
        var rootRow = remaining.remove(root);
        if (rootRow == null) rootRow = department(provider, root);
        ordered.add(rootRow);
        var children = new HashMap<String, List<Department>>();
        for (var row : remaining.values()) children.computeIfAbsent(row.parentId(), unused -> new ArrayList<>()).add(row);
        var queue = new ArrayDeque<String>();
        queue.add(root);
        while (!queue.isEmpty()) {
            for (var row : children.getOrDefault(queue.removeFirst(), List.of())) {
                if (!seen.add(row.id())) throw WeLinkClient.unavailable();
                ordered.add(row);
                queue.addLast(row.id());
            }
        }
        if (ordered.size() != remaining.size() + 1) throw WeLinkClient.unavailable();
        if (ordered.size() > MAX_SNAPSHOT_ROWS) throw WeLinkClient.unavailable();
        if (ordered.size() <= limit) return new Page<>(ordered, null);
        String snapshotId = UUID.randomUUID().toString();
        synchronized (snapshots) {
            snapshots.values().removeIf(snapshot -> snapshot.expiresAt().isBefore(clock.instant()));
            int rows = snapshots.values().stream().mapToInt(snapshot -> snapshot.rows().size()).sum();
            while (!snapshots.isEmpty() && (snapshots.size() >= 64 || rows + ordered.size() > MAX_SNAPSHOT_ROWS)) {
                rows -= snapshots.remove(snapshots.keySet().iterator().next()).rows().size();
            }
            snapshots.put(snapshotId, new Snapshot(WeLinkClient.cacheKey(provider), root,
                    List.copyOf(ordered), clock.instant().plusSeconds(300)));
        }
        return new Page<>(ordered.subList(0, limit), snapshotId + ":" + limit);
    }
    private Page<Department> snapshotPage(IdentityProviderDescriptor provider, String root, String cursor, int limit) {
        String[] parts = cursor.split(":", -1);
        if (parts.length != 2) throw WeLinkClient.unavailable();
        int offset = offset(parts[1]);
        synchronized (snapshots) {
            var snapshot = snapshots.get(parts[0]);
            if (snapshot == null || !snapshot.expiresAt().isAfter(clock.instant())
                    || !snapshot.root().equals(root) || !snapshot.authority().equals(WeLinkClient.cacheKey(provider))
                    || offset < 1 || offset >= snapshot.rows().size()) throw WeLinkClient.unavailable();
            int end = Math.min(offset + limit, snapshot.rows().size());
            return new Page<>(snapshot.rows().subList(offset, end),
                    end == snapshot.rows().size() ? null : parts[0] + ":" + end);
        }
    }
    public Page<Person> departmentPeople(IdentityProviderDescriptor provider, String departmentId, String cursor, int limit) {
        var config = new WeLinkConfiguration(provider.configuration());
        checkLimit(limit);
        int page = cursor == null ? 1 : offset(cursor);
        if (page < 1 || page > config.maxPages()) throw WeLinkClient.unavailable();
        int size = Math.min(limit, config.pageSize());
        var response = client.directory(provider, "/api/contact/v2/user/list", Map.of("deptCode", departmentId,
                "pageNo", Integer.toString(page), "pageSize", Integer.toString(size)));
        var data = rows(response);
        var people = new ArrayList<Person>();
        var ids = new HashSet<String>();
        for (JsonNode row : data) {
            String id = WeLinkClient.required(row, "userId");
            if (!ids.add(id)) throw WeLinkClient.unavailable();
            var memberships = new LinkedHashSet<String>();
            JsonNode codes = row.path("deptCodes");
            if (codes.isArray()) {
                for (JsonNode code : codes) {
                    if (!code.isValueNode() || code.isNull() || code.asText().isBlank()) throw WeLinkClient.unavailable();
                    memberships.add(code.asText());
                }
            } else if (codes.isTextual() && !codes.asText().isBlank()) {
                for (String code : codes.asText().split(",")) {
                    if (code.isBlank()) throw WeLinkClient.unavailable();
                    memberships.add(code.trim());
                }
            } else if (!codes.isMissingNode() && !codes.isNull()) throw WeLinkClient.unavailable();
            memberships.add(departmentId); // Each listing itself confirms this membership.
            String primary = optional(row, "mainDeptCode");
            if (primary != null) memberships.add(primary);
            people.add(new Person(id, WeLinkClient.required(row, "userNameCn"), optional(row, "employeeId"),
                    primary, List.copyOf(memberships), config.status(row.path("userStatus").asText()),
                    optional(row, "mobileNumber"), optional(row, "userEmail")));
        }
        return new Page<>(people, finished(response, data.size(), size, config) ? null : Integer.toString(page + 1));
    }
    private static Department mapDepartment(JsonNode row, WeLinkConfiguration config) {
        String title = optional(row, "deptNameCn");
        String parent = optional(row, "parentCode");
        if (parent != null && config.csv("rootParentCodes", "").contains(parent)) parent = null;
        return new Department(WeLinkClient.required(row, "deptCode"), parent,
                title == null ? WeLinkClient.required(row, "deptNameEn") : title);
    }
    private static JsonNode rows(JsonNode response) {
        JsonNode rows = response.path("data");
        if (!rows.isArray()) throw WeLinkClient.unavailable();
        return rows;
    }
    private static boolean finished(JsonNode response, int count, int size, WeLinkConfiguration config) {
        if (count > size) throw WeLinkClient.unavailable();
        JsonNode hasMore = response.path("hasMore");
        return count < size || (hasMore.isBoolean() && !hasMore.asBoolean())
                || (hasMore.isValueNode() && !hasMore.isBoolean()
                && config.value("noMoreValue", "0").equals(hasMore.asText()));
    }
    private static String optional(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isValueNode()) throw WeLinkClient.unavailable();
        return value.asText().isBlank() ? null : value.asText();
    }
    private static int offset(String cursor) {
        try { int n = cursor == null ? 0 : Integer.parseInt(cursor); if (n < 0) throw new NumberFormatException(); return n; }
        catch (NumberFormatException bad) { throw new IllegalArgumentException("Invalid WeLink cursor"); }
    }
    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid WeLink page limit");
    }
}
