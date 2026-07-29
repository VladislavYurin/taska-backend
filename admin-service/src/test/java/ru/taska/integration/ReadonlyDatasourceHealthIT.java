package ru.taska.integration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ReadonlyDatasourceHealthIT extends AbstractIT {

    @Autowired
    @Qualifier("authDb")
    private ReactiveHealthIndicator authDbHealth;

    @Test
    void authReadonlyHealth_isUp() {
        Health health = authDbHealth.health()
                .block(Duration.ofSeconds(5));

        Assertions.assertThat(health).isNotNull();
        Assertions.assertThat(health.getStatus()).isEqualTo(Status.UP);
    }
}
