package org.tie.config;

/** Stateless protections against reprocessing a source file (§2, §4.1). */
public record DuplicateProtection(boolean watermark, PostSuccessAction postSuccessAction) {

    public static final DuplicateProtection NONE = new DuplicateProtection(false, null);

    public DuplicateProtection {
        postSuccessAction = postSuccessAction == null ? PostSuccessAction.NONE : postSuccessAction;
    }
}
