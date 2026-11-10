package com.caseware.fanout.executor;

import com.caseware.fanout.domain.Lane;
import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.executor.ExecutorConfig.LaneWeights;
import com.caseware.fanout.port.Delivery;
import com.caseware.fanout.port.WorkQueue;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The three load-request lanes (I4) seen as one {@link WorkQueue}. {@code receive} asks the
 * {@link LanePoller} for the lane order and returns the first non-empty lane's batch (G-13); every other
 * operation goes to the lane named in the request ({@code body().lane()}), so a requeue stays in its lane.
 * Thread-safe if the lane queues are.
 */
public final class LaneQueues implements WorkQueue<LoadRequest> {

    private final Map<Lane, WorkQueue<LoadRequest>> lanes;
    private final LanePoller poller;

    public LaneQueues(Map<Lane, ? extends WorkQueue<LoadRequest>> lanes, LaneWeights weights) {
        this.lanes = new EnumMap<>(lanes);
        for (Lane lane : Lane.values()) {
            if (!this.lanes.containsKey(lane)) {
                throw new IllegalArgumentException("missing queue for lane " + lane);
            }
        }
        this.poller = new LanePoller(weights);
    }

    /** Only the last lane tried gets {@code wait}, so empty lanes never long-poll ahead of busy ones. */
    @Override
    public List<Delivery<LoadRequest>> receive(int max, Duration wait) {
        List<Lane> order = poller.nextOrder();
        for (int i = 0; i < order.size(); i++) {
            boolean last = i == order.size() - 1;
            List<Delivery<LoadRequest>> got = lanes.get(order.get(i)).receive(max, last ? wait : Duration.ZERO);
            if (!got.isEmpty()) {
                return got;
            }
        }
        return List.of();
    }

    @Override
    public void extendVisibility(Delivery<LoadRequest> d, Duration by) {
        laneOf(d).extendVisibility(d, by);
    }

    @Override
    public void ack(Delivery<LoadRequest> d) {
        laneOf(d).ack(d);
    }

    @Override
    public void requeueWithDelay(Delivery<LoadRequest> d, Duration delay) {
        laneOf(d).requeueWithDelay(d, delay);
    }

    @Override
    public void requeueWithDelay(Delivery<LoadRequest> d, LoadRequest body, Duration delay) {
        laneOf(d).requeueWithDelay(d, body, delay);
    }

    @Override
    public void deadLetter(Delivery<LoadRequest> d, String reason) {
        laneOf(d).deadLetter(d, reason);
    }

    private WorkQueue<LoadRequest> laneOf(Delivery<LoadRequest> d) {
        return lanes.get(d.body().lane());
    }
}
