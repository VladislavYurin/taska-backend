package ru.taska.config.props;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Secrets deliberately have no generated toString(). */
@ConfigurationProperties("notification.telegram")
public class TelegramProperties {
    private boolean enabled;
    private String botUsername = "";
    private String botToken = "";
    private String webhookSecret = "";
    private Duration timeout = Duration.ofSeconds(10);
    private Duration linkTtl = Duration.ofMinutes(10);

    @PostConstruct
    public void validate() {
        if (enabled && (botUsername == null || !botUsername.matches("[A-Za-z0-9_]{5,32}")
                || !botToken.matches("[0-9]+:[A-Za-z0-9_-]+")
                || !webhookSecret.matches("[A-Za-z0-9_-]{32,256}"))) {
            throw new IllegalArgumentException("Configure Telegram bot username, token and webhook secret before enabling it");
        }
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0
                || linkTtl.compareTo(Duration.ofMinutes(1)) < 0 || linkTtl.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("Invalid Telegram timeout or link TTL");
        }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBotUsername() { return botUsername; }
    public void setBotUsername(String value) { this.botUsername = value; }
    public String getBotToken() { return botToken; }
    public void setBotToken(String value) { this.botToken = value; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String value) { this.webhookSecret = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { this.timeout = value; }
    public Duration getLinkTtl() { return linkTtl; }
    public void setLinkTtl(Duration value) { this.linkTtl = value; }
}
