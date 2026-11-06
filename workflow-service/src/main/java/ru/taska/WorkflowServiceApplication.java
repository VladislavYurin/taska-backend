package ru.taska;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcRepositoriesAutoConfiguration;
import ru.taska.workflowbootstrap.LegacyWorkflowConfiguration;
import ru.taska.workflowbootstrap.TenantStorageConfiguration;

@SpringBootApplication(scanBasePackages="ru.taska.workflowbootstrap", exclude=DataR2dbcRepositoriesAutoConfiguration.class)
@Import({LegacyWorkflowConfiguration.class,TenantStorageConfiguration.class})
@ConfigurationPropertiesScan
public class WorkflowServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowServiceApplication.class, args);
    }

}
