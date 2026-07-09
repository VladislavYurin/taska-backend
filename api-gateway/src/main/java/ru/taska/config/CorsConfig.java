package ru.taska.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import ru.taska.config.props.CorsProperties;

@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    private final CorsProperties corsProperties;

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowCredentials(corsProperties.allowCredentials());
        corsConfiguration.setAllowedOrigins(corsProperties.allowedOrigins().stream().toList());
        corsConfiguration.setAllowedMethods(corsProperties.allowedMethods().stream().toList());
        corsConfiguration.setAllowedHeaders(corsProperties.allowedHeaders().stream().toList());
        corsConfiguration.setExposedHeaders(corsProperties.exposedHeaders().stream().toList());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);

        return new CorsWebFilter(source);
    }
}
