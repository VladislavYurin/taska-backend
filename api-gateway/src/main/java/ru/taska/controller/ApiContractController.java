package ru.taska.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.util.Map;

/** Publishes the authoritative OpenAPI contract in JSON as well as the static YAML. */
@RestController
public class ApiContractController {
    private final Map<String, Object> contract;

    public ApiContractController() throws IOException {
        try (var input = new ClassPathResource("static/openapi.yml").getInputStream()) {
            this.contract = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
    }

    @GetMapping(value = "/v3/api-docs", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getContract() {
        return contract;
    }
}
