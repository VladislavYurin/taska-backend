package nullable;

/**
 * Обёртка для значения, которое может находиться в трёх состояниях:
 * <p>{@code present == false} — значение отсутствует.
 * {@code present == true, value == null} — значение = {@code null}.
 * {@code present == true, value != null} — ненулевое значение.</p>
 */
public record NullableField<T>(boolean present, T value) {

    public static <T> NullableField<T> absent() {
        return new NullableField<>(false, null);
    }

    public static <T> NullableField<T> of(T value) {
        return new NullableField<>(true, value);
    }

    public T orElse(T other) {
        return present ? value : other;
    }
}
