package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/** Only explicitly supplied booleans are accepted; identity and email are server-owned. */
public class NotificationPreferencesPatchDto {
    private Boolean emailEnabled;
    private Boolean telegramEnabled;
    public Boolean getEmailEnabled() { return emailEnabled; }
    public Boolean getTelegramEnabled() { return telegramEnabled; }
    @JsonProperty("emailEnabled")
    public void setEmailEnabled(JsonNode value) { emailEnabled = bool(value); }
    @JsonProperty("telegramEnabled")
    public void setTelegramEnabled(JsonNode value) { telegramEnabled = bool(value); }
    @JsonAnySetter
    public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown notification setting"); }
    private static boolean bool(JsonNode value) {
        if(value==null || !value.isBoolean()) throw new IllegalArgumentException("Notification settings must be booleans");
        return value.booleanValue();
    }
}
