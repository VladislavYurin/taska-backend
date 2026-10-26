package ru.taska.service;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.dto.AvatarDto;
import ru.taska.dto.UserDetailsDto;
import ru.taska.entity.User;
import ru.taska.repository.UserRepository;
import ru.taska.storage.client.StorageClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserDirectoryServiceTest {
    @Test
    void stalledStorageDoesNotDelayOrReorderTheDirectory() {
        var users = mock(UserRepository.class);
        var access = mock(DirectoryAccess.class);
        var storage = mock(StorageClient.class);
        var membership = mock(DirectoryMembershipAccess.class);
        when(membership.check(any())).thenReturn(Mono.empty());
        var service = new UserDirectoryService(users, access, storage, membership);
        UUID actor = UUID.randomUUID();
        var slow = UserDetailsDto.builder().userId(UUID.randomUUID()).displayName("A Person").email("a@example.test")
                .avatar(AvatarDto.builder().objectKey("slow-avatar").build()).build();
        var normal = UserDetailsDto.builder().userId(UUID.randomUUID()).displayName("B Person").email("b@example.test").build();
        when(access.requireActor(actor, false)).thenReturn(Mono.just(new User()));
        when(users.searchDirectory(anyString(), anyInt(), anyLong())).thenReturn(Flux.just(slow, normal));
        when(storage.createPresignedDownloadUrl("slow-avatar")).thenReturn(Mono.never());
        StepVerifier.withVirtualTime(() -> service.search(actor, "person", 0, 20))
                .thenAwait(Duration.ofMillis(251)).assertNext(result -> {
                    assertThat(result.getItemsList()).extracting(row -> row.getId())
                            .containsExactly(slow.userId().toString(), normal.userId().toString());
                    assertThat(result.getItems(0).hasAvatarUrl()).isFalse();
                }).verifyComplete();
    }
}
