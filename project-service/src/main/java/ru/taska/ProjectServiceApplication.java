package ru.taska;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Import;
import org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcRepositoriesAutoConfiguration;
import ru.taska.projectbootstrap.LegacyProjectConfiguration;
import ru.taska.projectbootstrap.TenantStorageConfiguration;

@SpringBootApplication(scanBasePackages = "ru.taska.projectbootstrap", exclude = DataR2dbcRepositoriesAutoConfiguration.class)
@Import({LegacyProjectConfiguration.class, TenantStorageConfiguration.class})
@EnableScheduling
@ConfigurationPropertiesScan
public class ProjectServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectServiceApplication.class, args);
    }

}
