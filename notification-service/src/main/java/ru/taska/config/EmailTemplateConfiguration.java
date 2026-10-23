package ru.taska.config;

import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

@Configuration
public class EmailTemplateConfiguration {
    @Bean
    public SpringTemplateEngine emailTemplateEngine() {
        var engine = new SpringTemplateEngine();
        engine.addTemplateResolver(resolver("*.html", TemplateMode.HTML, 1));
        engine.addTemplateResolver(resolver("*.txt", TemplateMode.TEXT, 2));
        return engine;
    }

    private ClassLoaderTemplateResolver resolver(String pattern, TemplateMode mode, int order) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setResolvablePatterns(Set.of(pattern));
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setOrder(order);
        return resolver;
    }
}
