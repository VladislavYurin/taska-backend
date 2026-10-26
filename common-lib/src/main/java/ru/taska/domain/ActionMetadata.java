package ru.taska.domain;

import java.util.UUID;
import reactor.util.context.ContextView;

/** Internal attribution. Only channel may be exported in public history/comments. */
public record ActionMetadata(ActionChannel channel, UUID tokenId) {
    public static final ActionMetadata NONE = new ActionMetadata(null, null);
    public static ActionMetadata current(ContextView context) { return context.getOrDefault(ActionMetadata.class, NONE); }
}
