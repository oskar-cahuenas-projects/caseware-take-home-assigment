package com.caseware.fanout.port;

import static com.caseware.fanout.domain.Fixtures.T0;
import static com.caseware.fanout.domain.Fixtures.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class SealedHierarchyTest {

    /** Compiles only while the switch is exhaustive over exactly the permitted subtypes (no default). */
    private static String classify(LoaderException e) {
        return switch (e) {
            case LoaderException.Throttled t -> "retry after " + t.retryAfter().toSeconds();
            case LoaderException.Transient t -> "transient";
            case LoaderException.NotFound n -> "not found";
            case LoaderException.Permanent p -> "permanent";
        };
    }

    private static String classify(Claim c) {
        return switch (c) {
            case Claim.Claimed cl -> "claimed";
            case Claim.AlreadyFresh f -> "fresh";
            case Claim.InFlightElsewhere i -> "elsewhere " + i.ownerWorkerId();
            case Claim.PendingPublish p -> "pending " + p.eventId();
        };
    }

    @Test
    @DisplayName("AC2: LoaderException permits exactly Throttled, Transient, NotFound, Permanent")
    void loaderExceptionIsSealedOverFour() {
        assertThat(LoaderException.class.isSealed()).isTrue();
        assertThat(Arrays.stream(LoaderException.class.getPermittedSubclasses()).map(Class::getSimpleName))
                .containsExactlyInAnyOrder("Throttled", "Transient", "NotFound", "Permanent");

        assertThat(classify(new LoaderException.Throttled("eng-1", Duration.ofSeconds(30)))).isEqualTo("retry after 30");
        assertThat(classify(new LoaderException.Transient("eng-1", null))).isEqualTo("transient");
        assertThat(classify(new LoaderException.NotFound("eng-1"))).isEqualTo("not found");
        assertThat(classify(new LoaderException.Permanent("eng-1"))).isEqualTo("permanent");
    }

    @Test
    @DisplayName("AC2: Throttled rejects a negative retryAfter; messages carry the engagement ID only")
    void throttledValidation() {
        assertThatThrownBy(() -> new LoaderException.Throttled("eng-1", Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LoaderException.NotFound("eng-1").getMessage()).isEqualTo("engagementId=eng-1");
        assertThat(new LoaderException.Permanent("eng-1").engagementId()).isEqualTo("eng-1");
    }

    @Test
    @DisplayName("AC3: Claim is sealed over the four G-04 variants")
    void claimIsSealedOverFour() {
        assertThat(Claim.class.isSealed()).isTrue();
        assertThat(Arrays.stream(Claim.class.getPermittedSubclasses()).map(Class::getSimpleName))
                .containsExactlyInAnyOrder("Claimed", "AlreadyFresh", "InFlightElsewhere", "PendingPublish");

        assertThat(classify(new Claim.Claimed(T0))).isEqualTo("claimed");
        assertThat(classify(new Claim.AlreadyFresh(OptionalLong.empty(), T0))).isEqualTo("fresh");
        assertThat(classify(new Claim.InFlightElsewhere("worker-2", T0))).isEqualTo("elsewhere worker-2");
        assertThat(classify(new Claim.PendingPublish(state(OptionalLong.of(3)), "eng-1:3", List.of("job-1"))))
                .isEqualTo("pending eng-1:3");
    }

    @Test
    @DisplayName("AC3: Claim payloads are validated and PendingPublish copies jobIds")
    void claimPayloadValidation() {
        assertThatThrownBy(() -> new Claim.Claimed(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Claim.AlreadyFresh(null, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Claim.InFlightElsewhere(" ", T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim.PendingPublish(state(OptionalLong.of(3)), "", List.of("job-1")))
                .isInstanceOf(IllegalArgumentException.class);

        List<String> jobs = new ArrayList<>(List.of("job-1"));
        var pending = new Claim.PendingPublish(state(OptionalLong.of(3)), "eng-1:3", jobs);
        jobs.add("job-2");
        assertThat(pending.jobIds()).containsExactly("job-1");
    }

    @Test
    @DisplayName("Delivery requires a receipt and a receive count of at least 1")
    void deliveryValidation() {
        assertThatThrownBy(() -> new Delivery<>("body", "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Delivery<>("body", "r-1", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Delivery<String>(null, "r-1", 1)).isInstanceOf(NullPointerException.class);
    }
}
