package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.directory.v1.DirectoryUser;
import ru.taska.api.directory.v1.SearchUsersResponse;
import ru.taska.dto.UserDetailsDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.UserRepository;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.security.trust.GrpcTenantServiceClient;
import ru.taska.storage.client.StorageClient;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class UserDirectoryService {
    private final UserRepository users;
    private final GrpcTenantServiceClient grpcTenantServiceClient;
    private final DirectoryAccess access;
    private final StorageClient storage;
    private final DirectoryMembershipAccess membership;
    @org.springframework.beans.factory.annotation.Value("${directory.avatar-timeout:250ms}")
    private java.time.Duration avatarTimeout = java.time.Duration.ofMillis(250);
    private static final int MEMBERSHIP_BATCH_SIZE = 100;

    /**
     * Поиск пользователей по запросу {@code query}. Пользователи должны находится в тоже tenant что и {@code actor}
     * @param actor
     * @param query
     * @param page
     * @param pageSize
     * @return
     */
    public Mono<SearchUsersResponse> search(UUID actor, String query, int page, int pageSize) {
        return Mono.deferContextual(context -> {
            if (!context.hasKey(VerifiedTenantContext.class))
                return Mono.error(new DomainException(
                        DomainStatus.UNAUTHENTICATED,
                        "Verified tenant context is required"
                ));
            VerifiedTenantContext verifiedTenantContext = VerifiedTenantContext.require(context);
            String tenantId = verifiedTenantContext.tenantId();
            if (actor == null || !actor.toString().equals(verifiedTenantContext.actorId())) {
                return Mono.error(new DomainException(
                        DomainStatus.PERMISSION_DENIED,
                        "Actor does not match authenticated identity"
                ));
            }
            return access.requireActor(actor, false)
                    .flatMap(user ->
                                    membership.check(user, tenantId)
                                            .then(Mono.defer(() -> {
                                                String normalized = query == null ?
                                                        "" :
                                                        query.strip().toLowerCase(Locale.ROOT);
                                                int length = normalized.codePointCount(0, normalized.length());
                                                if (length < 3 || length > 100 ||
                                                    normalized.codePoints().anyMatch(Character::isISOControl) ||
                                                    page < 0 || page > 1000 || pageSize < 1 || pageSize > 50
                                                ) {
                                                    return Mono.error(new DomainException(
                                                            DomainStatus.INVALID_ARGUMENT,
                                                            "Query must contain 3-100 characters, page 0-1000 and pageSize 1-50")
                                                    );
                                                }
                                                String pattern = "%" + normalized.replace("!", "!!")
                                                        .replace("%", "!%")
                                                        .replace("_", "!_") +
                                                                 "%";
                                                long membersToSkip = page * pageSize;
                                                return Flux.fromStream(() ->
                                                            Stream.iterate(0L, offset ->
                                                                    offset + MEMBERSHIP_BATCH_SIZE
                                                            )
                                                        )
                                                        .concatMap( offset ->
                                                                users.searchDirectory(
                                                                        pattern,
                                                                        MEMBERSHIP_BATCH_SIZE,
                                                                        offset
                                                                ).collectList(), 0
                                                        )
                                                        .takeUntil(rows ->
                                                                rows.size() < MEMBERSHIP_BATCH_SIZE
                                                        )
                                                            .filter(rows -> !rows.isEmpty())
                                                        .concatMap(rows -> {
                                                                    List<UUID> uuids = rows.stream()
                                                                            .map(UserDetailsDto::userId)
                                                                            .toList();
                                                                    return grpcTenantServiceClient.batchValidateMemberships(tenantId, uuids)
                                                                            .switchIfEmpty(Mono.error(new DomainException(
                                                                                    DomainStatus.UNAVAILABLE,
                                                                                    "Tenant service returned no membership batch"
                                                                            )))
                                                                            .flatMapIterable(batch ->
                                                                                    IntStream.range(0, batch.getItemsCount())
                                                                                            .filter(i -> batch.getItems(i).getFound())
                                                                                            .filter(i -> {
                                                                                                var state = batch.getItems(i).getMembership().getState();
                                                                                                return state == Membership.StateValue.STATE_ACTIVE ||
                                                                                                       state == Membership.StateValue.STATE_SUSPENDED;
                                                                                            })
                                                                                            .mapToObj(rows::get)
                                                                                            .toList()
                                                                            );
                                                                    }, 0
                                                        )
                                                        .skip(membersToSkip)
                                                        .take(pageSize + 1L)
                                                        .collectList()
                                                        .flatMap(rows -> {
                                                            boolean hasMore = rows.size() > pageSize;
                                                            int itemCount = Math.min(rows.size(), pageSize);
                                                            return Flux.fromIterable(rows.subList(0, itemCount))
                                                                    .flatMapSequential(this::toDirectoryUser, 16)
                                                                    .collectList()
                                                                    .map(items -> SearchUsersResponse.newBuilder()
                                                                            .addAllItems(items)
                                                                            .setHasMore(hasMore)
                                                                            .build());
                                                        });
                                            }))
                    );
        });
    }

    private Mono<DirectoryUser> toDirectoryUser(UserDetailsDto row) {
        var builder = DirectoryUser.newBuilder()
                .setId(row.userId().toString())
                .setDisplayName(row.displayName())
                .setEmail(row.email());
        if (row.avatar() == null) {
            return Mono.just(builder.build());
        }
        return storage.createPresignedDownloadUrl(row.avatar().getObjectKey())
                .timeout(avatarTimeout)
                .map(url -> builder.setAvatarUrl(url).build())
                .defaultIfEmpty(builder.build())
                .onErrorReturn(builder.build());
    }
}