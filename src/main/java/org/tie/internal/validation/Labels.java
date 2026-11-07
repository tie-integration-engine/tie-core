package org.tie.internal.validation;

import org.tie.config.ChannelDefinition;
import org.tie.config.FieldDefinition;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.ProvidedStreamChannel;
import org.tie.config.SftpChannel;

/** Prefixes that locate a configuration problem: {@code layout 'alta', field 'nif': …}. */
final class Labels {

    private Labels() {
    }

    static String layout(Layout layout, int index) {
        return isBlank(layout.name()) ? "layout #" + (index + 1) : "layout '" + layout.name() + "'";
    }

    static String field(String layoutLabel, FieldDefinition field, int index) {
        return layoutLabel + ", field " + (isBlank(field.name()) ? "#" + (index + 1) : "'" + field.name() + "'");
    }

    static String channel(String path, ChannelDefinition channel) {
        String kind = switch (channel) {
            case SftpChannel ignored -> "SFTP";
            case LocalDirectoryChannel ignored -> "local directory";
            case ProvidedStreamChannel ignored -> "provided stream";
        };
        return path + " (" + kind + ")";
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
