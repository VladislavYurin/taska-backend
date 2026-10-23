package ru.taska.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.taska.testutil.EmailTestSupport.parts;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import ru.taska.config.EmailTemplateConfiguration;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;

class EmailMessageFactoryTest {
    public static EmailMessageFactory factory() {
        return factory("https://taska.ozero.dev/#", "#4f46e5", "");
    }

    private static EmailMessageFactory factory(String appUrl, String accent, String workspace) {
        return new EmailMessageFactory(new JavaMailSenderImpl(),
                new EmailTemplateConfiguration().emailTemplateEngine(), appUrl, accent, workspace);
    }

    @Test
    void notificationHasAlternativePartsEscapedHtmlAndHashRoutingLink() throws Exception {
        var notification = Notification.builder().title("Issue <script>alert(1)</script>")
                .body("Body <img src=x onerror=alert(1)> & text").issueKey("TAS-128")
                .notificationType(NotificationType.ISSUE_UPDATED).build();
        var message = factory().notification(notification, "user@example.test");
        message.saveChanges();
        var body = parts(message);
        assertThat(body).containsOnlyKeys("text", "html");
        assertThat(body.get("text")).contains(notification.getBody(), "https://taska.ozero.dev/#/browse/TAS-128");
        assertThat(body.get("html")).contains("&lt;script&gt;", "&lt;img", "&amp; text",
                        "https://taska.ozero.dev/#/browse/TAS-128")
                .doesNotContain("<script>", "<img src=x", " th:", "${");
        assertThat(message.getSubject()).isEqualTo(notification.getTitle());
        assertThat(message.getContentType()).contains("multipart/");
        assertThat(body.get("html").getBytes(StandardCharsets.UTF_8).length).isLessThan(30 * 1024);
    }

    @Test
    void noIssueHasNoActionAndPlainTextIsReadableWithoutHtml() {
        var body = parts(factory().notification(Notification.builder().title("Welcome to Taska")
                .body("Finish signing up").notificationType(NotificationType.USER_INVITED).build(), "u@example.test"));
        assertThat(body.get("html")).doesNotContain("/browse/", "Open TAS-128", "${");
        assertThat(body.get("text")).contains("Welcome to Taska", "Finish signing up").doesNotContain("<table", "null");
    }

    @Test
    void keyIsEncodedAsSingleSegmentForLocalAndHashRoutes() {
        for (String base : List.of("https://taska.ozero.dev/#/", "http://localhost:5173/")) {
            var body = parts(factory(base, "#4f46e5", "").notification(Notification.builder().title("Issue")
                    .body("Updated").issueKey("A/B #?").build(), "u@example.test"));
            assertThat(body.get("text")).contains(base.replaceAll("/+$", "") + "/browse/A%2FB%20%23%3F");
        }
    }

    @Test
    void invitationIncludesEnglishUtcExpiryAndSameOneTimeLinkInBothParts() throws Exception {
        String link = "https://taska.ozero.dev/?token=" + "a".repeat(43);
        var message = factory("https://taska.ozero.dev/#", "#0d9488", "Acme <script>")
                .invitation("new@example.test", link, Instant.parse("2026-10-21T14:30:00Z"));
        var body = parts(message);
        for (String part : body.values()) assertThat(part).contains(link, "21 Oct 2026, 14:30 UTC");
        assertThat(body.get("html")).contains("Acme &lt;script&gt;", "#0d9488", "#17171b")
                .doesNotContain("<script>", " th:", "${");
        assertThat(message.getSubject()).isEqualTo("You're invited to Taska");
        assertThat(body.get("html").getBytes(StandardCharsets.UTF_8).length).isLessThan(30 * 1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {"red", "#fff", "#000000;background:url(https://evil.test)", ""})
    void invalidAccentFallsBackWithoutInjectingCss(String accent) {
        var html = parts(factory("https://taska.ozero.dev/#", accent, "").notification(Notification.builder()
                .title("Issue").body("Updated").issueKey("TAS-1").build(), "u@example.test")).get("html");
        assertThat(html).contains("#4f46e5", "#ffffff").doesNotContain("evil.test");
    }

    @Test
    void longContentFitsMailSizeLimitWithoutMutatingStoredNotification() {
        String original = "<&>😀".repeat(10000);
        var notification = Notification.builder().title(original).body(original).issueKey("TAS-1").build();
        var html = parts(factory().notification(notification, "u@example.test")).get("html");
        assertThat(html.getBytes(StandardCharsets.UTF_8).length).isLessThan(30 * 1024);
        assertThat(notification.getBody()).isEqualTo(original);
    }

    @Test
    void untrustedAppUrlsFailAtConfigurationTime() {
        for (String url : List.of("javascript:alert(1)", "http://evil.test", "https://user:pw@taska.test/",
                "https://taska.test/?token=secret", "https://taska.test/#bad")) {
            assertThatThrownBy(() -> factory(url, "#4f46e5", "")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
