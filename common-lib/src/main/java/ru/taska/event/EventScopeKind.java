package ru.taska.event;

/** Trust boundary used to route and deduplicate domain events. */
public enum EventScopeKind {
    TENANT,
    GLOBAL_USER,
    GLOBAL_PLATFORM
}
