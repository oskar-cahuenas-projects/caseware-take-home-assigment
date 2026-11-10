package com.caseware.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Guards NFR-08: main code takes time only from the injected Clock and Scheduler. */
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class MainCodeTimeSourcesTest {

    private static final Pattern FORBIDDEN =
            Pattern.compile("Instant\\.now\\(|System\\.currentTimeMillis\\(|System\\.nanoTime\\(|Thread\\.sleep\\(");

    @Test
    @DisplayName("NFR-08: no direct time sources or sleeps in src/main")
    void noDirectTimeSources() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .flatMap(MainCodeTimeSourcesTest::offendingLines)
                    .toList();
            assertThat(offenders).isEmpty();
        }
    }

    private static Stream<String> offendingLines(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            return java.util.stream.IntStream.range(0, lines.size())
                    .filter(i -> FORBIDDEN.matcher(lines.get(i)).find())
                    .mapToObj(i -> file + ":" + (i + 1) + ": " + lines.get(i).trim());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
