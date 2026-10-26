package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.directory.v1.DirectoryUser;
import ru.taska.api.directory.v1.SearchUsersResponse;
import ru.taska.dto.UserDetailsDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.UserRepository;
import ru.taska.storage.client.StorageClient;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserDirectoryService {
    private final UserRepository users;
    private final DirectoryAccess access;
    private final StorageClient storage;
    private final DirectoryMembershipAccess membership;
    @org.springframework.beans.factory.annotation.Value("${directory.avatar-timeout:250ms}")
    private java.time.Duration avatarTimeout = java.time.Duration.ofMillis(250);

    public Mono<SearchUsersResponse> search(UUID actor, String query, int page, int pageSize) {
        return access.requireActor(actor, false).flatMap(user -> membership.check(user).then(Mono.defer(() -> {
            String normalized = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
            int length = normalized.codePointCount(0, normalized.length());
            if (length < 3 || length > 100 || normalized.codePoints().anyMatch(Character::isISOControl) || page < 0 || page > 1000 || pageSize < 1 || pageSize > 50) {
                return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,
                        "Query must contain 3-100 characters, page 0-1000 and pageSize 1-50"));
            }
            String pattern = "%" + normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            return users.searchDirectory(pattern, pageSize + 1, (long) page * pageSize).collectList()
                    .flatMap(rows -> reactor.core.publisher.Flux.fromIterable(rows.subList(0, Math.min(pageSize, rows.size())))
                            .flatMapSequential(this::toDirectoryUser, 16).collectList()
                            .map(items -> SearchUsersResponse.newBuilder().addAllItems(items)
                                    .setHasMore(rows.size() > pageSize).build()));
        })));
    }

    private Mono<DirectoryUser> toDirectoryUser(UserDetailsDto row) {
        var builder = DirectoryUser.newBuilder().setId(row.userId().toString())
                .setDisplayName(row.displayName()).setEmail(row.email());
        if (row.avatar() == null) {
            return Mono.just(builder.build());
        }
        return storage.createPresignedDownloadUrl(row.avatar().getObjectKey())
                .timeout(avatarTimeout)
                .map(url -> builder.setAvatarUrl(url).build()).defaultIfEmpty(builder.build())
                .onErrorReturn(builder.build());
    }
}
