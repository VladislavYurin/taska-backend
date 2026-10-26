package ru.taska.domain.dto;
import lombok.Getter;
import lombok.Setter;
/** Name validation is shared and counts Unicode code points. */
@Getter @Setter
public class OAuthSignupRequestDto {
    private String signupToken;
    private String displayName;
}
