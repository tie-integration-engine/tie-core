package org.tie;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * INV-2. The build-time guarantee is the enforcer {@code bannedDependencies} rule in
 * {@code tie-core/pom.xml}; this test checks the same thing on the test runtime.
 */
class CoreDoesNotDependOnSpringTest {

    @Test
    @DisplayName("AC-106 tie-core has no org.springframework dependency")
    void noSpringOnTheTestRuntime() {
        assertThatThrownBy(() -> Class.forName("org.springframework.core.SpringVersion"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.springframework.boot.SpringApplication"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
