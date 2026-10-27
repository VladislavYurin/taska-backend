package ru.taska.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.util.MimeType;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import ru.taska.domain.dto.CreateWorkflowRequestDto;
import ru.taska.domain.dto.PatchWorkflowRequestDto;
import tools.jackson.databind.json.JsonMapper;

/** Bounded status mappings only; unrelated request DTOs keep the global memory limit. */
@Configuration
public class WorkflowBindingCodecConfig implements WebFluxConfigurer {
    static final int MAX_BODY_BYTES = 1024 * 1024;
    private final JsonMapper mapper;

    public WorkflowBindingCodecConfig(JsonMapper mapper) { this.mapper = mapper; }

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer codecs) {
        var decoder = new JacksonJsonDecoder(mapper) {
            @Override
            public boolean canDecode(ResolvableType type, MimeType mimeType) {
                return (type.resolve() == CreateWorkflowRequestDto.class || type.resolve() == PatchWorkflowRequestDto.class)
                        && super.canDecode(type, mimeType);
            }
        };
        decoder.setMaxInMemorySize(MAX_BODY_BYTES);
        codecs.customCodecs().register(decoder);
    }
}
