package ru.taska.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import ru.taska.config.props.TelegramProperties;

class TelegramConfigurationTest {
    @ParameterizedTest
    @NullAndEmptySource
    void enabledIntegrationRejectsMissingBotUsername(String username) {
        var properties = new TelegramProperties();
        properties.setEnabled(true);
        properties.setBotUsername(username);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Configure Telegram bot username");
    }
}
