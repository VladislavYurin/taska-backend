package ru.taska.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import ru.taska.domain.Notification;
import ru.taska.mapper.NotificationMapper;

/** Builds alternative plain-text/HTML bodies on the SMTP worker, without loading comment text. */
@Component
public class EmailMessageFactory {
    private static final String DEFAULT_ACCENT = "#4f46e5";
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter
            .ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private final JavaMailSender sender;
    private final SpringTemplateEngine engine;
    private final String appUrl;
    private final String fromAddress;
    private final Map<String, String> brand;

    public EmailMessageFactory(JavaMailSender sender, SpringTemplateEngine emailTemplateEngine,
            @Value("${notification.app-url:http://localhost:5173}") String appUrl,
            @Value("${notification.brand.accent:#4f46e5}") String accent,
            @Value("${notification.brand.workspace-name:}") String workspaceName,
            @Value("${notification.email.from:${spring.mail.username:}}") String fromAddress) {
        URI uri = URI.create(appUrl);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))
                || uri.getFragment() != null && !uri.getFragment().isEmpty() && !"/".equals(uri.getFragment())) {
            throw new IllegalArgumentException("Notification app URL must be an HTTPS root or hash root (local HTTP allowed)");
        }
        this.sender = sender;
        this.engine = emailTemplateEngine;
        this.fromAddress = fromAddress == null ? "" : fromAddress.strip();
        this.appUrl = appUrl.replaceAll("/+$", "");
        String safeAccent = accent != null && HEX.matcher(accent).matches()
                ? accent.toLowerCase(Locale.ROOT) : DEFAULT_ACCENT;
        this.brand = new LinkedHashMap<>();
        brand.put("accent", safeAccent);
        brand.put("accentFg", foreground(safeAccent));
        brand.put("workspaceName", clipped(workspaceName, 100));
    }

    public MimeMessage notification(Notification notification, String recipient) {
        var context = context();
        var values = new LinkedHashMap<String, String>();
        values.put("title", clipped(notification.getTitle(), 256));
        values.put("body", clipped(NotificationMapper.safeBody(notification), 1600));
        String key = notification.getIssueKey();
        values.put("issueKey", clipped(key, 128));
        context.setVariable("notification", values);
        context.setVariable("actionUrl", key == null || key.isBlank() || key.length() > 128 ? null
                : appUrl + "/browse/" + UriUtils.encodePathSegment(key, StandardCharsets.UTF_8));
        return message("notification", values.get("title"), recipient, context);
    }

    public MimeMessage invitation(String recipient, String acceptUrl, Instant expiresAt) {
        var context = context();
        context.setVariable("acceptUrl", acceptUrl);
        context.setVariable("expiresAt", EXPIRY.format(expiresAt));
        return message("invitation", "You're invited to Taska", recipient, context);
    }

    private Context context() {
        var context = new Context(Locale.ENGLISH);
        context.setVariable("brand", brand);
        return context;
    }

    private MimeMessage message(String template, String subject, String recipient, Context context) {
        if (fromAddress.isBlank()) {
            throw new MailPreparationException("Configure notification.email.from or spring.mail.username");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(recipient);
            helper.setSubject(subject == null ? "" : subject);
            helper.setText(engine.process(template + ".txt", context), engine.process(template + ".html", context));
            return message;
        } catch (MessagingException error) {
            throw new MailPreparationException("Could not build Taska email", error);
        }
    }

    private static String clipped(String value, int maxCodePoints) {
        if (value == null || value.codePointCount(0, value.length()) <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints)) + "…";
    }

    private static String foreground(String accent) {
        double fill = luminance(accent);
        return contrast(fill, 1) >= contrast(fill, luminance("#17171b")) ? "#ffffff" : "#17171b";
    }

    private static double contrast(double a, double b) {
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(String hex) {
        double[] channels = new double[3];
        for (int i = 0; i < channels.length; i++) {
            double channel = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            channels[i] = channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
    }
}
