package ru.taska.oauth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Servlet OAuth authorization server; user identities remain owned by auth-service. */
@org.springframework.boot.context.properties.EnableConfigurationProperties(
    OAuthServerProperties.class)
@SpringBootApplication
public class OAuthServerApplication {
  public static void main(String[] args) {
    SpringApplication.run(OAuthServerApplication.class, args);
  }
}
