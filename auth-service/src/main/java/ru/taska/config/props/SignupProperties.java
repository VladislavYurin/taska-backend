package ru.taska.config.props;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("taska.signup")
public class SignupProperties {
    private boolean enabled = false;
    @Min(1) private int ipAttemptsPerHour = 10;
}
