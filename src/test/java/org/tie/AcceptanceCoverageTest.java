package org.tie;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Traceability between {@code docs/spec.md} and the test suites of every module.
 *
 * <p>An acceptance criterion counts as covered when its id ({@code AC-nnn}) appears in a test source
 * of any module. Criteria not covered yet must be listed in {@code acceptance-pending.txt}; each
 * phase removes the ids it covers and the list must be empty at the end of F9.
 */
class AcceptanceCoverageTest {

    private static final Pattern SPEC_ROW = Pattern.compile("(?m)^\\| AC-(\\d+) \\|");
    private static final Pattern AC_ID = Pattern.compile("AC-(\\d+)");
    private static final String PENDING_RESOURCE = "/acceptance-pending.txt";

    @Test
    void everyAcceptanceCriterionIsCoveredOrPending() throws IOException {
        Path root = projectRoot();
        Path spec = root.resolve("docs/spec.md");
        Assumptions.assumeTrue(Files.isRegularFile(spec),
                "docs/spec.md not found from " + root + "; acceptance coverage not checked");

        Set<Integer> specified = ids(SPEC_ROW, Files.readString(spec));
        Set<Integer> covered = coveredByTests(root);
        covered.retainAll(specified);
        Set<Integer> pending = pending();

        assertThat(difference(pending, specified))
                .as("ids in acceptance-pending.txt that are not in the spec")
                .isEmpty();
        assertThat(intersection(pending, covered))
                .as("ids already covered by a test: remove them from acceptance-pending.txt")
                .isEmpty();
        assertThat(difference(specified, union(covered, pending)))
                .as("acceptance criteria with no test and not listed as pending")
                .isEmpty();
    }

    private static Path projectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("tie-core"))) {
                return candidate;
            }
        }
        return dir;
    }

    private static Set<Integer> coveredByTests(Path root) throws IOException {
        Set<Integer> covered = new TreeSet<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                Path tests = module.resolve("src/test/java");
                if (!Files.isDirectory(tests)) {
                    continue;
                }
                try (Stream<Path> sources = Files.walk(tests)) {
                    sources.filter(path -> path.toString().endsWith(".java"))
                            .filter(path -> !path.getFileName().toString().equals("AcceptanceCoverageTest.java"))
                            .forEach(path -> covered.addAll(ids(AC_ID, read(path))));
                }
            }
        }
        return covered;
    }

    private static Set<Integer> pending() throws IOException {
        try (InputStream in = Objects.requireNonNull(
                AcceptanceCoverageTest.class.getResourceAsStream(PENDING_RESOURCE), PENDING_RESOURCE)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Set<Integer> pending = new TreeSet<>();
            content.lines()
                    .map(line -> line.replaceFirst("#.*", "").strip())
                    .filter(line -> !line.isEmpty())
                    .forEach(line -> pending.addAll(ids(AC_ID, line)));
            return pending;
        }
    }

    private static Set<Integer> ids(Pattern pattern, String text) {
        Set<Integer> ids = new TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            ids.add(Integer.parseInt(matcher.group(1)));
        }
        return ids;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<Integer> union(Set<Integer> a, Set<Integer> b) {
        Set<Integer> result = new TreeSet<>(a);
        result.addAll(b);
        return result;
    }

    private static Set<Integer> intersection(Set<Integer> a, Set<Integer> b) {
        Set<Integer> result = new TreeSet<>(a);
        result.retainAll(b);
        return result;
    }

    private static Set<Integer> difference(Set<Integer> a, Set<Integer> b) {
        Set<Integer> result = new TreeSet<>(a);
        result.removeAll(b);
        return result;
    }
}
