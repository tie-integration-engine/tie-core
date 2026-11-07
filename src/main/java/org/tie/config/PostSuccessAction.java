package org.tie.config;

/** What happens to a source file after it has been processed successfully. */
public sealed interface PostSuccessAction {

    PostSuccessAction NONE = new None();
    PostSuccessAction DELETE = new Delete();

    /**
     * Move to {@code remoteFolder}: relative to the channel directory unless it starts with
     * {@code /}. The folder is not created.
     */
    static PostSuccessAction move(String remoteFolder) {
        return new Move(remoteFolder);
    }

    record None() implements PostSuccessAction {
    }

    record Move(String remoteFolder) implements PostSuccessAction {
    }

    record Delete() implements PostSuccessAction {
    }
}
