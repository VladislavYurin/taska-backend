package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.SignupProperties;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.util.DirectoryInput;
import ru.taska.util.DisplayNames;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmailSignupService {
    private final SignupProperties properties;
    private final DirectoryInput input;
    private final SignupIpLimiter ipLimiter;
    private final EmailSignupTransaction transaction;
    private final ObjectProvider<AuthProviderCatalog> catalogs;

    public Mono<Void> signup(String email, String displayName, String ip, String requestId) {
        return Mono.defer(() -> {
            if (!properties.isEnabled()) return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,
                    "Signup is disabled", Map.of("reason", "SIGNUP_DISABLED")));
            String canonical = input.email(email);
            String name = DisplayNames.normalize(displayName);
            if (ip == null || ip.isBlank() || ip.length() > 128) {
                return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Client address is required"));
            }
            return ipLimiter.reserve(ip).then(Mono.defer(() -> transaction.signup(canonical, name, requestId)));
        });
    }
    public boolean enabled() { return properties.isEnabled(); }
    public List<String> providers() {
        var enabled = catalogs.stream().flatMap(catalog -> catalog.enabledProviders().stream()).collect(java.util.stream.Collectors.toSet());
        return List.of("GOOGLE", "GITHUB", "APPLE").stream().filter(enabled::contains).toList();
    }
}
