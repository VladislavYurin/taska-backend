package ru.taska.testutil;

import jakarta.mail.Part;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EmailTestSupport {
    private EmailTestSupport() { }

    public static Map<String, String> parts(Part message) {
        try {
            if (message instanceof MimeMessage mime) mime.saveChanges();
            Map<String, String> result = new LinkedHashMap<>();
            if (message.isMimeType("text/plain")) result.put("text", (String) message.getContent());
            else if (message.isMimeType("text/html")) result.put("html", (String) message.getContent());
            else if (message.getContent() instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) result.putAll(parts(multipart.getBodyPart(i)));
            }
            return result;
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    public static List<String> recipients(MimeMessage message) {
        try {
            return java.util.Arrays.stream(message.getAllRecipients()).map(Object::toString).toList();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
