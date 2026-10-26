package ru.taska.domain.util;

import java.util.List;

/** A bounded ascending board page; totalCount includes rows before the cursor. */
public record BoardCursorPage<T>(List<T> items, long totalCount, boolean hasMore) {}
