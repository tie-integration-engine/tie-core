package org.tie.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Immutable description of an import or an export (§4.1). Construction never validates: use
 * {@code Tie.validate} to get every problem at once.
 */
public record IntegrationDefinition(
        String id,
        Direction direction,
        List<Layout> layouts,
        List<ChannelDefinition> inputChannels,
        ChannelDefinition outputChannel,
        DeliveryStrategy deliveryStrategy,
        RejectionThreshold rejectionThreshold,
        ConsumerErrorPolicy consumerErrorPolicy,
        DuplicateProtection duplicateProtection,
        ResultReport resultReport,
        String outputNamePattern) {

    public IntegrationDefinition {
        layouts = layouts == null ? List.of() : List.copyOf(layouts);
        inputChannels = inputChannels == null ? List.of() : List.copyOf(inputChannels);
        deliveryStrategy = deliveryStrategy == null ? DeliveryStrategy.STREAMING : deliveryStrategy;
        rejectionThreshold = rejectionThreshold == null ? RejectionThreshold.NONE : rejectionThreshold;
        consumerErrorPolicy = consumerErrorPolicy == null ? ConsumerErrorPolicy.REJECT_LINE : consumerErrorPolicy;
        duplicateProtection = duplicateProtection == null ? DuplicateProtection.NONE : duplicateProtection;
        resultReport = resultReport == null ? ResultReport.NONE : resultReport;
    }

    public static Builder importing(String id) {
        return new Builder().id(id).direction(Direction.IMPORT);
    }

    public static Builder exporting(String id) {
        return new Builder().id(id).direction(Direction.EXPORT);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The layout with this exact name, if any. */
    public Optional<Layout> layout(String name) {
        return layouts.stream().filter(layout -> layout.name() != null && layout.name().equals(name)).findFirst();
    }

    public Builder toBuilder() {
        return new Builder().id(id).direction(direction).layouts(layouts).inputChannels(inputChannels)
                .outputChannel(outputChannel).deliveryStrategy(deliveryStrategy).rejectionThreshold(rejectionThreshold)
                .consumerErrorPolicy(consumerErrorPolicy).duplicateProtection(duplicateProtection)
                .resultReport(resultReport).outputNamePattern(outputNamePattern);
    }

    public static final class Builder {
        private String id;
        private Direction direction;
        private final List<Layout> layouts = new ArrayList<>();
        private final List<ChannelDefinition> inputChannels = new ArrayList<>();
        private ChannelDefinition outputChannel;
        private DeliveryStrategy deliveryStrategy;
        private RejectionThreshold rejectionThreshold;
        private ConsumerErrorPolicy consumerErrorPolicy;
        private DuplicateProtection duplicateProtection;
        private ResultReport resultReport;
        private String outputNamePattern;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder direction(Direction direction) {
            this.direction = direction;
            return this;
        }

        public Builder layout(Layout layout) {
            this.layouts.add(layout);
            return this;
        }

        public Builder layout(Layout.Builder layout) {
            return layout(layout.build());
        }

        public Builder layouts(List<Layout> layouts) {
            this.layouts.clear();
            this.layouts.addAll(layouts);
            return this;
        }

        public Builder inputChannel(ChannelDefinition inputChannel) {
            this.inputChannels.add(inputChannel);
            return this;
        }

        public Builder inputChannels(List<ChannelDefinition> inputChannels) {
            this.inputChannels.clear();
            this.inputChannels.addAll(inputChannels);
            return this;
        }

        public Builder outputChannel(ChannelDefinition outputChannel) {
            this.outputChannel = outputChannel;
            return this;
        }

        public Builder deliveryStrategy(DeliveryStrategy deliveryStrategy) {
            this.deliveryStrategy = deliveryStrategy;
            return this;
        }

        public Builder rejectionThreshold(RejectionThreshold rejectionThreshold) {
            this.rejectionThreshold = rejectionThreshold;
            return this;
        }

        public Builder consumerErrorPolicy(ConsumerErrorPolicy consumerErrorPolicy) {
            this.consumerErrorPolicy = consumerErrorPolicy;
            return this;
        }

        public Builder duplicateProtection(DuplicateProtection duplicateProtection) {
            this.duplicateProtection = duplicateProtection;
            return this;
        }

        public Builder resultReport(ResultReport resultReport) {
            this.resultReport = resultReport;
            return this;
        }

        public Builder outputNamePattern(String outputNamePattern) {
            this.outputNamePattern = outputNamePattern;
            return this;
        }

        public IntegrationDefinition build() {
            return new IntegrationDefinition(id, direction, layouts, inputChannels, outputChannel, deliveryStrategy,
                    rejectionThreshold, consumerErrorPolicy, duplicateProtection, resultReport, outputNamePattern);
        }
    }
}
