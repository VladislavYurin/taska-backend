package ru.taska.security.pat;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "personal-tokens")
public class PersonalTokenProperties {
    @Min(1) private int maxLifetimeDays = 90;
    @Min(1) private int maxActiveTokens = 10;
}
