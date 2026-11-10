package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.Candidate;
import com.caseware.fanout.domain.Cursor;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.port.CandidateSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracking Service query API stand-in (I2): rows per {@code (templateId, branchId)} in insertion order; the
 * cursor token is the offset of the next row. Strongly consistent (GSI lag is out of scope). Thread-safe.
 */
public final class InMemoryCandidateSource implements CandidateSource {

    private final Map<String, List<Candidate>> rows = new ConcurrentHashMap<>();

    public void add(String templateId, String branchId, Candidate candidate) {
        rows.compute(key(templateId, branchId), (k, list) -> {
            List<Candidate> next = list == null ? new ArrayList<>() : new ArrayList<>(list);
            next.add(candidate);
            return List.copyOf(next);
        });
    }

    public int count(String templateId, String branchId) {
        return rows.getOrDefault(key(templateId, branchId), List.of()).size();
    }

    @Override
    public Page<Candidate> find(String templateId, String branchId, Cursor cursor, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        List<Candidate> all = rows.getOrDefault(key(templateId, branchId), List.of());
        int from = cursor.equals(Cursor.START) ? 0 : Integer.parseInt(cursor.token());
        int to = Math.min(all.size(), from + limit);
        Optional<Cursor> next = to < all.size() ? Optional.of(new Cursor(Integer.toString(to))) : Optional.empty();
        return new Page<>(all.subList(Math.min(from, to), to), next);
    }

    private static String key(String templateId, String branchId) {
        return templateId + "/" + branchId;
    }
}
