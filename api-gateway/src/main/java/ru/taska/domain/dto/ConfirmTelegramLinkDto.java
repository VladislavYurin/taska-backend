package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Confirmation accepts only the current link identifier. */
public class ConfirmTelegramLinkDto {
    @NotNull
    private UUID linkId;
    public UUID getLinkId() { return linkId; }
    public void setLinkId(UUID value) { linkId=value; }
    @JsonAnySetter
    public void unknown(String name,Object value) { throw new IllegalArgumentException("Unknown Telegram confirmation field"); }
}
