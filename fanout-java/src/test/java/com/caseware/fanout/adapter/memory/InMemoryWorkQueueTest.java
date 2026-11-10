package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.port.Delivery;
import com.caseware.fanout.testkit.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class InMemoryWorkQueueTest {

    private static final Duration VISIBILITY = Duration.ofMinutes(3);
    private static final Duration WAIT = Duration.ofSeconds(20);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
    private final InMemoryWorkQueue<String> queue =
            new InMemoryWorkQueue<>("publish", clock, VISIBILITY, 5, Duration.ofMinutes(15));

    private Delivery<String> receiveOne() {
        List<Delivery<String>> got = queue.receive(1, WAIT);
        assertThat(got).hasSize(1);
        return got.get(0);
    }

    @Test
    @DisplayName("receive returns up to max visible messages in send order, first receiveCount = 1")
    void receiveInOrder() {
        queue.send("a");
        queue.send("b");
        queue.send("c");

        List<Delivery<String>> got = queue.receive(2, WAIT);

        assertThat(got).extracting(Delivery::body).containsExactly("a", "b");
        assertThat(got).extracting(Delivery::receiveCount).containsOnly(1);
        assertThat(queue.receive(10, WAIT)).extracting(Delivery::body).containsExactly("c");
        assertThat(queue.receive(10, WAIT)).isEmpty();
    }

    @Test
    @DisplayName("AC1: an unacked message reappears after visibility expires with receiveCount + 1")
    void visibilityExpiryRedelivers() {
        queue.send("a");
        receiveOne();

        clock.advance(VISIBILITY.minusMillis(1));
        assertThat(queue.receive(10, WAIT)).isEmpty();

        clock.advance(Duration.ofMillis(1));
        Delivery<String> again = receiveOne();
        assertThat(again.body()).isEqualTo("a");
        assertThat(again.receiveCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC2: extendVisibility keeps the message invisible until now + by")
    void extendVisibility() {
        queue.send("a");
        Delivery<String> d = receiveOne();

        clock.advance(Duration.ofMinutes(2));
        queue.extendVisibility(d, Duration.ofMinutes(3));     // new deadline: t = 5 min

        clock.advance(Duration.ofMinutes(2));                 // t = 4 min, past the original 3 min
        assertThat(queue.receive(10, WAIT)).isEmpty();

        clock.advance(Duration.ofMinutes(1));                 // t = 5 min
        assertThat(receiveOne().receiveCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC3: requeueWithDelay replaces the message; delay capped at 15 min; count restarts")
    void requeueWithDelayCapsAndResets() {
        queue.send("a");
        clock.advance(VISIBILITY);
        receiveOne();
        clock.advance(VISIBILITY);
        Delivery<String> second = receiveOne();
        assertThat(second.receiveCount()).isEqualTo(2);

        queue.requeueWithDelay(second, Duration.ofMinutes(20));
        assertThat(queue.depth()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(15).minusMillis(1));
        assertThat(queue.receive(10, WAIT)).isEmpty();

        clock.advance(Duration.ofMillis(1));
        Delivery<String> fresh = receiveOne();
        assertThat(fresh.body()).isEqualTo("a");
        assertThat(fresh.receiveCount()).isEqualTo(1);
        assertThat(fresh.receipt()).isNotEqualTo(second.receipt());
    }

    @Test
    @DisplayName("AC3: the requeued message is a new message; the old receipt no longer acts on it")
    void requeueOldReceiptIsDead() {
        queue.send("a");
        Delivery<String> d = receiveOne();
        queue.requeueWithDelay(d, Duration.ZERO);

        queue.ack(d);                                         // stale: no-op
        assertThat(receiveOne().receiveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC4: the receive after maxReceiveCount moves the message to the DLQ instead of delivering it")
    void maxReceiveCountDeadLetters() {
        queue.send("poison");
        for (int i = 1; i <= 5; i++) {
            assertThat(receiveOne().receiveCount()).isEqualTo(i);
            clock.advance(VISIBILITY);
        }

        assertThat(queue.receive(10, WAIT)).isEmpty();
        assertThat(queue.depth()).isZero();
        assertThat(queue.deadLetters()).singleElement().satisfies(dl -> {
            assertThat(dl.body()).isEqualTo("poison");
            assertThat(dl.receiveCount()).isEqualTo(5);
            assertThat(dl.reason()).contains("maxReceiveCount");
        });
    }

    @Test
    @DisplayName("AC4: a message behind a poison message is still delivered in the same receive")
    void deadLetteringDoesNotBlockOthers() {
        queue.send("poison");
        for (int i = 1; i <= 5; i++) {
            receiveOne();
            clock.advance(VISIBILITY);
        }
        queue.send("ok");

        assertThat(queue.receive(1, WAIT)).extracting(Delivery::body).containsExactly("ok");
        assertThat(queue.deadLetters()).hasSize(1);
    }

    @Test
    @DisplayName("AC5: deadLetter moves the message with its reason and removes it from the queue")
    void explicitDeadLetter() {
        queue.send("a");
        Delivery<String> d = receiveOne();

        queue.deadLetter(d, "NOT_FOUND");
        clock.advance(VISIBILITY);

        assertThat(queue.receive(10, WAIT)).isEmpty();
        assertThat(queue.depth()).isZero();
        assertThat(queue.deadLetters()).singleElement().satisfies(dl -> {
            assertThat(dl.body()).isEqualTo("a");
            assertThat(dl.reason()).isEqualTo("NOT_FOUND");
            assertThat(dl.receiveCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("AC6: a stale receipt (after redelivery) is a no-op for ack, extend, requeue and deadLetter")
    void staleReceiptIsNoOp() {
        queue.send("a");
        Delivery<String> first = receiveOne();
        clock.advance(VISIBILITY);
        Delivery<String> second = receiveOne();

        queue.ack(first);
        queue.extendVisibility(first, Duration.ofHours(1));
        queue.requeueWithDelay(first, Duration.ZERO);
        queue.deadLetter(first, "stale");

        assertThat(queue.depth()).isEqualTo(1);
        assertThat(queue.deadLetters()).isEmpty();
        clock.advance(VISIBILITY);
        assertThat(receiveOne().receiveCount()).isEqualTo(3);

        queue.ack(second);                                    // also stale now
        assertThat(queue.depth()).isEqualTo(1);
    }

    @Test
    @DisplayName("ack with the current receipt deletes the message; a second ack is a no-op")
    void ackDeletes() {
        queue.send("a");
        Delivery<String> d = receiveOne();
        queue.ack(d);
        queue.ack(d);
        clock.advance(VISIBILITY);
        assertThat(queue.depth()).isZero();
        assertThat(queue.receive(10, WAIT)).isEmpty();
    }

    @Test
    @DisplayName("send with a delay is capped like requeue")
    void sendWithDelay() {
        queue.send("later", Duration.ofMinutes(1));
        assertThat(queue.receive(10, WAIT)).isEmpty();
        clock.advance(Duration.ofMinutes(1));
        assertThat(receiveOne().body()).isEqualTo("later");
    }

    @Test
    @DisplayName("invalid arguments are rejected")
    void invalidArguments() {
        assertThatThrownBy(() -> queue.receive(0, WAIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queue.send("a", Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryWorkQueue<String>("q", clock, VISIBILITY, 0, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC7: concurrent receivers never get the same message within one visibility window")
    void concurrentReceiversGetDistinctMessages() throws InterruptedException {
        int messages = 2_000;
        for (int i = 0; i < messages; i++) {
            queue.send("m" + i);
        }
        Set<String> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        List<Thread> receivers = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            receivers.add(Thread.ofVirtual().start(() -> {
                List<Delivery<String>> batch;
                while (!(batch = queue.receive(10, WAIT)).isEmpty()) {
                    for (Delivery<String> d : batch) {
                        if (!seen.add(d.body())) {
                            duplicates.incrementAndGet();
                        }
                        received.incrementAndGet();
                    }
                }
            }));
        }
        for (Thread t : receivers) {
            t.join();
        }

        assertThat(duplicates).hasValue(0);
        assertThat(received).hasValue(messages);
        assertThat(seen).hasSize(messages);
    }
}
