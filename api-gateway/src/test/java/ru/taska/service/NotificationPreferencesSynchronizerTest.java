package ru.taska.service;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;
import ru.taska.api.notification.v1.NotificationPreferencesResponse;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.transport.grpc.GrpcNotificationSettingsClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationPreferencesSynchronizerTest {
    @Test
    void coalescesProfilesBoundsWorkAndRetriesAfterFailureOrQueueSaturation() {
        var client=mock(GrpcNotificationSettingsClient.class);
        var gate=Sinks.<NotificationPreferencesResponse>one();
        when(client.get(any())).thenReturn(gate.asMono());
        var scheduler=VirtualTimeScheduler.create();
        try(var synchronizer=new NotificationPreferencesSynchronizer(client,scheduler)) {
            var first=context();
            for(int i=0;i<100;i++) synchronizer.submit(first);
            scheduler.advanceTimeBy(Duration.ZERO);
            verify(client,times(1)).get(first);
            var last=context();
            for(int i=0;i<400;i++) synchronizer.submit(context());
            synchronizer.submit(last);
            scheduler.advanceTimeBy(Duration.ZERO);
            // An unavailable dependency cannot accumulate unbounded RPCs.
            assertThat(mockingDetails(client).getInvocations()).hasSize(4);
            gate.tryEmitError(new IllegalStateException("unavailable"));
            scheduler.advanceTimeBy(Duration.ZERO);
            assertThat(mockingDetails(client).getInvocations().size()).isLessThan(400);
            clearInvocations(client);
            when(client.get(any())).thenReturn(Mono.just(NotificationPreferencesResponse.getDefaultInstance()));
            synchronizer.submit(first);
            synchronizer.submit(last);
            scheduler.advanceTimeBy(Duration.ZERO);
            verify(client).get(first);
            verify(client).get(last);
            synchronizer.close();
            synchronizer.submit(context());
            scheduler.advanceTimeBy(Duration.ZERO);
            verifyNoMoreInteractions(client);
        } finally {
            scheduler.dispose();
        }
    }

    private GatewayContext context() {
        return new GatewayContext("request","gateway",GatewayUserContext.builder()
                .userId(UUID.randomUUID().toString()).email("verified@example.test").build());
    }
}
