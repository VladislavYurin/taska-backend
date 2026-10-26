package ru.taska.oauth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OAuthUrlsTest {
    @Test void returnToRejectsExternalAndEncodedRedirects() {
        for (String unsafe : new String[]{null, "https://evil.example", "//evil.example", "/\\evil.example", "/%2f%2fevil.example", "/%252f%252fevil.example", "/%5cevil.example", "/%0d%0aLocation:evil", "javascript:alert(1)"})
            assertThat(OAuthUrls.returnTo(unsafe)).isEqualTo("/projects");
        assertThat(OAuthUrls.returnTo("/projects?view=mine#active")).isEqualTo("/projects?view=mine#active");
    }
    @Test void configuredOriginsRejectCredentialsAndRequireHttpsOutsideLocalhost() {
        assertThat(OAuthUrls.endpoint("https://ui.example.test", "/auth/callback")).isEqualTo("https://ui.example.test/auth/callback");
        assertThat(OAuthUrls.endpoint("http://localhost:3000", "/auth/callback")).isEqualTo("http://localhost:3000/auth/callback");
        for (String unsafe : new String[]{"http://ui.example.test", "https://user:secret@ui.example.test", "https://ui.example.test?next=evil", "javascript:evil"})
            assertThatThrownBy(() -> OAuthUrls.endpoint(unsafe, "/auth/callback")).isInstanceOf(IllegalArgumentException.class);
    }
}
