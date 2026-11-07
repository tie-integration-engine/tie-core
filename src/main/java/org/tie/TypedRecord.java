package org.tie;

/**
 * A transformed, typed record handed to the {@code RecordConsumer} (§4.2). A field that is absent
 * means "do not touch"; present with {@code null} means "empty" (INV-5).
 */
public interface TypedRecord extends RecordView {

    String layout();

    String sourceFileName();

    /** Ordinal of the record in its file, from 1; children are numbered right after their parent. */
    int lineNumber();

    /** The parent's line number for a child record, null for a root record. */
    Integer parentLineNumber();

    /** Whether any nested container of this record is present in the file ({@code []} counts, absent or null does not). */
    boolean childrenDeclared();

    /** Whether the container of the child layout {@code childLayout} is present in the file. */
    boolean childrenDeclared(String childLayout);
}
