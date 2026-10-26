package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@NoArgsConstructor
public class PasswordByTokenRequestDto {
    @NotNull @Setter private String token;
    @NotNull @Setter private String newPassword;
    private String displayName;
    @JsonIgnore private boolean displayNamePresent;
    public PasswordByTokenRequestDto(String token, String newPassword) { this.token = token; this.newPassword = newPassword; }
    @JsonSetter("displayName")
    public void setDisplayName(String displayName) { this.displayNamePresent = true; this.displayName = displayName; }
    public PasswordByTokenRequestDto token(String value) { setToken(value); return this; }
    public PasswordByTokenRequestDto newPassword(String value) { setNewPassword(value); return this; }
    public PasswordByTokenRequestDto displayName(String value) { setDisplayName(value); return this; }
}
