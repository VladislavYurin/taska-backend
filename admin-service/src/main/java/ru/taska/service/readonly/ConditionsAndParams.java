package ru.taska.service.readonly;

import java.util.List;

/**
 * Условия и параметры, построенные за один проход.
 */
record ConditionsAndParams(List<String> conditions, List<Object> params) {
}
