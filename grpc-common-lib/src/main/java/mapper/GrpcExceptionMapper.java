package mapper;

import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/**
 * Маппер доменных и произвольных исключений в gRPC-статусы.
 */
public final class GrpcExceptionMapper {

    private GrpcExceptionMapper() {
    }

    /**
     * Преобразует {@link DomainException} в {@link io.grpc.StatusRuntimeException}
     * на основе {@link DomainStatus}.
     *
     * @param ex доменное исключение
     * @return {@link io.grpc.StatusRuntimeException} с соответствующим статусом и сообщением
     */
    public static io.grpc.StatusRuntimeException toStatusRuntimeException(DomainException ex) {
        io.grpc.Status status = switch (ex.getStatus()) {
            case NOT_FOUND -> io.grpc.Status.NOT_FOUND;
            case ALREADY_EXISTS -> io.grpc.Status.ALREADY_EXISTS;
            case INVALID_ARGUMENT -> io.grpc.Status.INVALID_ARGUMENT;
            case FAILED_PRECONDITION -> io.grpc.Status.FAILED_PRECONDITION;
            case PERMISSION_DENIED -> io.grpc.Status.PERMISSION_DENIED;
            case UNAUTHENTICATED -> io.grpc.Status.UNAUTHENTICATED;
            case RESOURCE_EXHAUSTED -> io.grpc.Status.RESOURCE_EXHAUSTED;
            case UNAVAILABLE -> io.grpc.Status.UNAVAILABLE;
            case DEADLINE_EXCEEDED -> io.grpc.Status.DEADLINE_EXCEEDED;
            case CANCELLED -> io.grpc.Status.CANCELLED;
            case ABORTED -> io.grpc.Status.ABORTED;
            case OUT_OF_RANGE -> io.grpc.Status.OUT_OF_RANGE;
            case DATA_LOSS -> io.grpc.Status.DATA_LOSS;
            case UNIMPLEMENTED -> io.grpc.Status.UNIMPLEMENTED;
            case UNKNOWN -> io.grpc.Status.UNKNOWN;
            case INTERNAL -> io.grpc.Status.INTERNAL;
        };
        return status.withDescription(ex.getMessage()).asRuntimeException();
    }

    /**
     * Преобразует произвольное исключение в {@link io.grpc.StatusRuntimeException}.
     *
     * <p>Если исключение уже является {@link io.grpc.StatusRuntimeException} — возвращает его как есть.
     * В остальных случаях оборачивает в {@code INTERNAL} скрывая детали от клиента.</p>
     *
     * @param ex произвольное исключение
     * @return gRPC-исключение
     */
    public static Throwable toGrpcStatus(Throwable ex) {
        if (ex instanceof io.grpc.StatusRuntimeException) {
            return ex;
        }
        return io.grpc.Status.INTERNAL.withDescription("internal error").withCause(ex).asRuntimeException();
    }
}