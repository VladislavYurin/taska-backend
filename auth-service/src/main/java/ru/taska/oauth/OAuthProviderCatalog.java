package ru.taska.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.service.AuthProviderCatalog;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class OAuthProviderCatalog implements AuthProviderCatalog {
    private final OAuthProperties properties;
    @Override public List<String> enabledProviders() {
        return List.of("google", "github", "apple").stream().filter(this::configured).map(value -> value.toUpperCase(Locale.ROOT)).toList();
    }
    public boolean configured(String provider) {
        var client = properties.client(provider);
        return client.isEnabled() && present(client.getClientId()) && (provider.equals("apple")
                ? present(client.getTeamId()) && present(client.getKeyId()) && present(client.getPrivateKey()) : present(client.getClientSecret()));
    }
    public void require(String provider) { if (!configured(provider)) throw OAuthErrors.failure("PROVIDER_UNAVAILABLE"); }
    private boolean present(String value) { return value != null && !value.isBlank(); }
}
