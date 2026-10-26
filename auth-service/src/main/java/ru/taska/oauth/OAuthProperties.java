package ru.taska.oauth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** Provider secrets stay in auth-service; intentionally has no generated toString. */
@Getter @Setter
@ConfigurationProperties("taska.oauth")
public class OAuthProperties {
    private String apiBaseUrl = "https://api.taska.ozero.dev";
    private String uiBaseUrl = "https://taska.ozero.dev";
    private Duration timeout = Duration.ofSeconds(5);
    private Client google = new Client("https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", "https://www.googleapis.com/oauth2/v3/certs");
    private Client github = new Client("https://github.com/login/oauth/authorize", "https://github.com/login/oauth/access_token", null);
    private Client apple = new Client("https://appleid.apple.com/auth/authorize", "https://appleid.apple.com/auth/token", "https://appleid.apple.com/auth/keys");
    @Getter @Setter
    public static class Client {
        private boolean enabled = true;
        private String clientId = "";
        private String clientSecret = "";
        private String teamId = "";
        private String keyId = "";
        private String privateKey = "";
        private String authorizationUri;
        private String tokenUri;
        private String jwksUri;
        private String userUri = "https://api.github.com/user";
        private String emailsUri = "https://api.github.com/user/emails?per_page=100";
        public Client(String authorizationUri, String tokenUri, String jwksUri) {
            this.authorizationUri = authorizationUri; this.tokenUri = tokenUri; this.jwksUri = jwksUri;
        }
    }
    public Client client(String provider) {
        return switch (provider) { case "google" -> google; case "github" -> github; case "apple" -> apple; default -> throw OAuthErrors.failure("PROVIDER_UNAVAILABLE"); };
    }
}
