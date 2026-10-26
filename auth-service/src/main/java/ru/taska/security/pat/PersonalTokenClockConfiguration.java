package ru.taska.security.pat;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PersonalTokenClockConfiguration {
    @Bean Clock personalTokenClock() { return Clock.systemUTC(); }
}
