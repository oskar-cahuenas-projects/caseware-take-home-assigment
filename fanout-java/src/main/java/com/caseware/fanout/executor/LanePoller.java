package com.caseware.fanout.executor;

import com.caseware.fanout.domain.Lane;
import com.caseware.fanout.executor.ExecutorConfig.LaneWeights;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lane-selection strategy (§C, G-13): a fixed cycle of {@code publish + backfill + reconcile} slots built
 * by smooth weighted round-robin (6:3:1 → P B P P B P R P B P). Each receive takes the next slot.
 * Thread-safe.
 */
final class LanePoller {

    private final List<Lane> cycle;
    private final AtomicLong next = new AtomicLong();

    LanePoller(LaneWeights weights) {
        this.cycle = smoothWeightedRoundRobin(weights);
    }

    /**
     * Lanes to try for the next receive: the slot's lane first, then the other lanes in the order they next
     * appear in the cycle. A caller that moves on when a lane is empty is work-conserving (EC-14).
     */
    List<Lane> nextOrder() {
        int start = (int) (next.getAndIncrement() % cycle.size());
        LinkedHashSet<Lane> order = new LinkedHashSet<>();
        for (int i = 0; i < cycle.size(); i++) {
            order.add(cycle.get((start + i) % cycle.size()));
        }
        return List.copyOf(order);
    }

    List<Lane> cycle() {
        return cycle;
    }

    /** Each step: add every weight to its lane's credit, pick the highest credit (ties by enum order), subtract the total. */
    private static List<Lane> smoothWeightedRoundRobin(LaneWeights weights) {
        Map<Lane, Integer> credit = new EnumMap<>(Lane.class);
        int total = 0;
        for (Lane lane : Lane.values()) {
            credit.put(lane, 0);
            total += weights.weight(lane);
        }
        List<Lane> out = new ArrayList<>(total);
        for (int slot = 0; slot < total; slot++) {
            Lane best = null;
            for (Lane lane : Lane.values()) {
                credit.merge(lane, weights.weight(lane), Integer::sum);
                if (best == null || credit.get(lane) > credit.get(best)) {
                    best = lane;
                }
            }
            credit.merge(best, -total, Integer::sum);
            out.add(best);
        }
        return List.copyOf(out);
    }
}
