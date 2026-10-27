package ru.taska.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.util.MimeType;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import ru.taska.domain.dto.DeleteProjectIssueTypeRequestDto;
import tools.jackson.databind.json.JsonMapper;

/** Allows a bounded 5000-entry status mapping without increasing limits for other request DTOs. */
@Configuration
public class IssueTypeDeletionCodecConfig implements WebFluxConfigurer {
  static final int MAX_BODY_BYTES = 1024 * 1024;
  private final JsonMapper mapper;

  public IssueTypeDeletionCodecConfig(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
    // An optional body without Content-Type is resolved as octet-stream even when empty.
    // The generated route still consumes application/json, so nonempty untyped bodies are rejected.
    var decoder = new JacksonJsonDecoder(mapper, MediaType.APPLICATION_JSON,
        new MediaType("application", "*+json"), MediaType.APPLICATION_OCTET_STREAM) {
      @Override
      public boolean canDecode(ResolvableType elementType, MimeType mimeType) {
        return elementType.resolve() == DeleteProjectIssueTypeRequestDto.class
            && super.canDecode(elementType, mimeType);
      }
    };
    decoder.setMaxInMemorySize(MAX_BODY_BYTES);
    // registerWithDefaultConfig would overwrite this DTO-specific limit with the global default.
    configurer.customCodecs().register(decoder);
  }
}
