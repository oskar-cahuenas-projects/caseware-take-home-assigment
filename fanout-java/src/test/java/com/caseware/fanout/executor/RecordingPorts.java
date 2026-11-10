package com.caseware.fanout.executor;

import com.caseware.fanout.adapter.memory.SemaphoreConcurrencyLimiter;
import com.caseware.fanout.domain.EngagementLoadFailed;
import com.caseware.fanout.domain.EngagementStateObserved;
import com.caseware.fanout.domain.EngagementTemplateState;
import com.caseware.fanout.domain.FanoutJob;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.domain.Outcome;
import com.caseware.fanout.port.Claim;
import com.caseware.fanout.port.ConcurrencyLimiter;
import com.caseware.fanout.port.Delivery;
import com.caseware.fanout.port.EngagementLoader;
import com.caseware.fanout.port.FanoutJobStore;
import com.caseware.fanout.port.LoadLedger;
import com.caseware.fanout.port.LoaderException;
import com.caseware.fanout.port.ObservationPublisher;
import com.caseware.fanout.port.Permit;
import com.caseware.fanout.port.WorkQueue;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Port decorators that append each call's name to a shared list, for step-order assertions. */
final class RecordingPorts {

    private RecordingPorts() { }


    record RecordingQueue(WorkQueue<LoadRequest> d, List<String> calls) implements WorkQueue<LoadRequest> {
        public List<Delivery<LoadRequest>> receive(int max, Duration wait) { return d.receive(max, wait); }
        public void extendVisibility(Delivery<LoadRequest> x, Duration by) { calls.add("extend"); d.extendVisibility(x, by); }
        public void ack(Delivery<LoadRequest> x) { calls.add("ack"); d.ack(x); }
        public void requeueWithDelay(Delivery<LoadRequest> x, Duration delay) { calls.add("requeue"); d.requeueWithDelay(x, delay); }
        public void requeueWithDelay(Delivery<LoadRequest> x, LoadRequest body, Duration delay) { calls.add("requeue"); d.requeueWithDelay(x, body, delay); }
        public void deadLetter(Delivery<LoadRequest> x, String reason) { calls.add("deadLetter"); d.deadLetter(x, reason); }
    }

    record RecordingLedger(LoadLedger d, List<String> calls) implements LoadLedger {
        public Claim claim(String e, String j, String w, Duration l, Duration f, long k) { calls.add("claim"); return d.claim(e, j, w, l, f, k); }
        public void recordObservation(String e, EngagementTemplateState s, String id) { calls.add("recordObservation"); d.recordObservation(e, s, id); }
        public void markPublished(String e, String id) { calls.add("markPublished"); d.markPublished(e, id); }
        public void release(String e, String w) { calls.add("release"); d.release(e, w); }
    }

    record RecordingJobs(FanoutJobStore d, List<String> calls) implements FanoutJobStore {
        public boolean createIfAbsent(FanoutJob job) { return d.createIfAbsent(job); }
        public void recordOutcome(String j, String e, Outcome o) { calls.add("outcome:" + o); d.recordOutcome(j, e, o); }
        public Optional<FanoutJob> find(String j) { return d.find(j); }
    }

    record RecordingPublisher(ObservationPublisher d, List<String> calls) implements ObservationPublisher {
        public void publish(EngagementStateObserved e) { calls.add("publish"); d.publish(e); }
        public void publish(EngagementLoadFailed e) { calls.add("publishFailed"); d.publish(e); }
    }

    record RecordingLoader(EngagementLoader d, List<String> calls) implements EngagementLoader {
        public EngagementTemplateState load(String f, String e, Duration t) throws LoaderException { calls.add("load"); return d.load(f, e, t); }
    }

    record RecordingLimiter(SemaphoreConcurrencyLimiter delegate, List<String> calls) implements ConcurrencyLimiter {
        public Optional<Permit> tryAcquire(String firmId) {
            calls.add("permit");
            return delegate.tryAcquire(firmId).map(p -> (Permit) () -> { calls.add("permit.close"); p.close(); });
        }
        public int freePermits() { return delegate.freePermits(); }
    }
}
