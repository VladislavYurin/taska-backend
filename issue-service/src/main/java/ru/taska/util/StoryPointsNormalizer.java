package ru.taska.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class StoryPointsNormalizer {

    /**
     * Масштаб колонки {@code story_points} ({@code numeric(5, 2)}).
     */
    public static final int STORY_POINTS_SCALE = 2;

    private StoryPointsNormalizer() {}

    /**
     * Приводит story points к масштабу колонки с тем же округлением, что делает БД при записи.
     * Без этого значение из запроса (например, 3.0) отличается по scale от прочитанного из БД (3.00).
     */
    public static BigDecimal normalize(BigDecimal storyPoints) {
        return storyPoints == null ? null : storyPoints.setScale(STORY_POINTS_SCALE, RoundingMode.HALF_UP);
    }
}
