package ru.taska.error;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;
import ru.taska.domain.dto.RestErrorResponse;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayErrorHandler Tests")
class GatewayErrorHandlerTest {

    private static final String REQUEST_ID = "req-id";

    @Mock
    private ObjectMapper objectMapper;

    private final RestErrorMapper restErrorMapper = new RestErrorMapper();

    private GatewayErrorHandler handler;
    private MockServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        handler = new GatewayErrorHandler(objectMapper, restErrorMapper);
        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
    }

    @Test
    @DisplayName("Должен вернуть 401 при gRPC-ошибке UNAUTHENTICATED")
    void handleError_grpcUnauthenticated_setsStatus401() {
        StatusRuntimeException error = Status.UNAUTHENTICATED
                .withDescription("Token expired")
                .asRuntimeException();

        StepVerifier.create(handler.handleError(exchange, error, REQUEST_ID)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ArgumentCaptor<RestErrorResponse> captor = ArgumentCaptor.forClass(RestErrorResponse.class);
        verify(objectMapper).writeValueAsBytes(captor.capture());

        RestErrorResponse body = captor.getValue();
        assertThat(body.getCode()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.getMessage()).isEqualTo("Token expired");
    }

    @Test
    @DisplayName("Должен вернуть 500 при gRPC-ошибке OUT_OF_RANGE (неизвестный код)")
    void handleError_grpcOutOfRange_setsStatus500() {
        StatusRuntimeException error = Status.OUT_OF_RANGE.asRuntimeException();

        StepVerifier.create(handler.handleError(exchange, error, REQUEST_ID)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        ArgumentCaptor<RestErrorResponse> captor = ArgumentCaptor.forClass(RestErrorResponse.class);
        verify(objectMapper).writeValueAsBytes(captor.capture());

        RestErrorResponse body = captor.getValue();
        assertThat(body.getCode()).isEqualTo("OUT_OF_RANGE");
        assertThat(body.getMessage()).isEqualTo("OUT_OF_RANGE");
    }

    @Test
    @DisplayName("Должен вернуть HTTP-статус из ResponseStatusException")
    void handleError_responseStatusException_setsGivenStatus() {
        ResponseStatusException error = new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");

        StepVerifier.create(handler.handleError(exchange, error, REQUEST_ID)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ArgumentCaptor<RestErrorResponse> captor = ArgumentCaptor.forClass(RestErrorResponse.class);
        verify(objectMapper).writeValueAsBytes(captor.capture());

        RestErrorResponse body = captor.getValue();
        assertThat(body.getCode()).isEqualTo("FORBIDDEN");
        assertThat(body.getMessage()).isEqualTo("Access denied");
    }

    @Test
    @DisplayName("Должен вернуть 404 при ResponseStatusException без reason и использовать reason phrase")
    void handleError_responseStatusExceptionWithoutReason_setsStatusAndReasonPhrase() {
        ResponseStatusException error = new ResponseStatusException(HttpStatus.NOT_FOUND);

        StepVerifier.create(handler.handleError(exchange, error, REQUEST_ID)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ArgumentCaptor<RestErrorResponse> captor = ArgumentCaptor.forClass(RestErrorResponse.class);
        verify(objectMapper).writeValueAsBytes(captor.capture());

        RestErrorResponse body = captor.getValue();
        assertThat(body.getCode()).isEqualTo("NOT_FOUND");
        assertThat(body.getMessage()).isEqualTo("Not Found");
    }

    @Test
    @DisplayName("Должен вернуть 500 при непредвиденном исключении")
    void handleError_unexpectedException_setsStatus500() {
        RuntimeException error = new RuntimeException("Something went very wrong");

        StepVerifier.create(handler.handleError(exchange, error, REQUEST_ID)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        ArgumentCaptor<RestErrorResponse> captor = ArgumentCaptor.forClass(RestErrorResponse.class);
        verify(objectMapper).writeValueAsBytes(captor.capture());

        RestErrorResponse body = captor.getValue();
        assertThat(body.getCode()).isEqualTo("INTERNAL");
        assertThat(body.getMessage()).isEqualTo("Internal server error");
    }
}
