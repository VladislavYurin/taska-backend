package ru.taska.transport.grpc;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.admin.v1.ListAuditEntriesRequest;
import ru.taska.api.admin.v1.ListAuditEntriesResponse;
import ru.taska.api.admin.v1.ReactorAdminServiceGrpc;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/**
 * Адаптер — единственная точка, где RPC из proto становится доступным клиентам.
 * Не переопределённый метод молча отвечает UNIMPLEMENTED, поэтому проверяем покрытие всех RPC.
 */
@ExtendWith(MockitoExtension.class)
class GrpcAdminServiceAdapterTest {

    @Mock
    private GrpcAdminReadonlyService grpcAdminReadonlyService;

    @Mock
    private GrpcAdminUserManagementService grpcAdminUserManagementService;

    @InjectMocks
    private GrpcAdminServiceAdapter adapter;

    @Test
    void everyRpcFromProtoIsOverriddenByAdapter() {
        List<Method> rpcMethods = Arrays.stream(ReactorAdminServiceGrpc.AdminServiceImplBase.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getParameterCount() == 1 && Publisher.class.isAssignableFrom(m.getParameterTypes()[0]))
                .filter(m -> Publisher.class.isAssignableFrom(m.getReturnType()))
                .toList();

        Assertions.assertThat(rpcMethods).isNotEmpty();

        List<String> missing = rpcMethods.stream()
                .filter(m -> !isOverridden(m))
                .map(Method::getName)
                .toList();

        Assertions.assertThat(missing)
                .as("RPC без override в GrpcAdminServiceAdapter (клиент получит UNIMPLEMENTED)")
                .isEmpty();
    }

    @Test
    void listAuditEntries_delegatesToReadonlyService() {
        ListAuditEntriesResponse response = ListAuditEntriesResponse.newBuilder().setTotalRows(3).build();
        Mockito.when(grpcAdminReadonlyService.listAuditEntries(Mockito.any()))
                .thenReturn(Mono.just(response));

        StepVerifier.create(adapter.listAuditEntries(Mono.just(ListAuditEntriesRequest.getDefaultInstance())))
                .expectNext(response)
                .verifyComplete();
    }

    private static boolean isOverridden(Method rpc) {
        try {
            GrpcAdminServiceAdapter.class.getDeclaredMethod(rpc.getName(), rpc.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
