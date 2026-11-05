package ru.taska;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Import;
import ru.taska.issuebootstrap.LegacyIssueConfiguration;
import ru.taska.issuebootstrap.TenantStorageConfiguration;

@SpringBootApplication(
    scanBasePackages = "ru.taska.issuebootstrap",
    exclude = DataR2dbcRepositoriesAutoConfiguration.class)
@Import({LegacyIssueConfiguration.class, TenantStorageConfiguration.class})
@ConfigurationPropertiesScan
public class IssueServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(IssueServiceApplication.class, args);
  }
}
