package org.tie;

import java.util.List;

/** An invalid configuration or a missing SPI, with every problem found (§5.4). */
public class ConfigurationException extends TieException {

    private static final long serialVersionUID = 1L;

    private final List<String> problems;

    public ConfigurationException(List<String> problems) {
        super(message(problems));
        this.problems = List.copyOf(problems);
    }

    public ConfigurationException(String problem) {
        this(List.of(problem));
    }

    public List<String> getProblems() {
        return problems;
    }

    private static String message(List<String> problems) {
        if (problems.size() == 1) {
            return "Invalid configuration: " + problems.getFirst();
        }
        return "Invalid configuration (" + problems.size() + " problems):\n - " + String.join("\n - ", problems);
    }
}
