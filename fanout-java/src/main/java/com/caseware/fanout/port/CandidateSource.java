package com.caseware.fanout.port;

import com.caseware.fanout.domain.Candidate;
import com.caseware.fanout.domain.Cursor;
import com.caseware.fanout.domain.Page;

/** Tracking Service query API (I2): paged, read-only, eventually consistent. */
public interface CandidateSource {

    /** @param cursor where to resume; {@link Cursor#START} for the first page */
    Page<Candidate> find(String templateId, String branchId, Cursor cursor, int limit);
}
