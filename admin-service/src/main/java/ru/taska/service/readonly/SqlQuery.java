package ru.taska.service.readonly;

import java.util.List;

public record SqlQuery(
        String parameterizedQuery,
        List<Object> params
) {}
