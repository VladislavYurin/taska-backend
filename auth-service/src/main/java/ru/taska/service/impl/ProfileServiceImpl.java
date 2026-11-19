package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.dto.AvatarDto;
import ru.taska.dto.UserDetailsDto;
import ru.taska.dto.UserProfileDto;
import ru.taska.entity.User;
import ru.taska.entity.UserAvatar;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProfileMapper;
import ru.taska.repository.UserAvatarRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.security.trust.GrpcTenantServiceClient;
import ru.taska.service.DirectoryAccess;
import ru.taska.service.DirectoryMembershipAccess;
import ru.taska.service.ProfileService;
import ru.taska.storage.client.StorageClient;
import ru.taska.storage.dto.PresignedUploadResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {

    private final UserRepository userRepository;
    private final UserAvatarRepository userAvatarRepository;
    private final StorageClient storageClient;
    private final ProfileMapper profileMapper;
    private final TransactionalOperator transactionalOperator;
    private final DirectoryMembershipAccess membership;
    private final DirectoryAccess access;
    private final GrpcTenantServiceClient grpcTenantServiceClient;
    private static final int MEMBERSHIP_BATCH_SIZE = 100;

    @Override
    public Mono<User> updateMyProfile(UUID actorUserId, String displayName) {
        return Mono.defer(() -> userRepository.updateDisplayName(actorUserId, ru.taska.util.DisplayNames.normalize(displayName)))
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")));
    }

    @Override
    public Mono<String> getAvatarDownloadUrl(UUID actorUserId) {
        return verifyUserExists(actorUserId)
                .then(Mono.defer(() -> userAvatarRepository.findByUserId(actorUserId)))
                .flatMap(avatar -> storageClient.createPresignedDownloadUrl(avatar.getObjectKey()));
    }

    @Override
    public Mono<PresignedUploadResult> createAvatarUploadUrl(UUID actorUserId, String contentType, long sizeBytes) {
        return verifyUserExists(actorUserId)
                .then(Mono.defer(() -> storageClient.createPresignedUploadUrl(contentType, sizeBytes,
                        "avatars/" + actorUserId + "/" + UUID.randomUUID())));
    }

    @Override
    public Mono<UserProfileDto> getUserProfile(UUID userId) {
        return verifyUserExists(userId)
                .flatMap(user -> userAvatarRepository.findByUserId(userId)
                        .flatMap(avatar -> storageClient.createPresignedDownloadUrl(avatar.getObjectKey())
                                .map(url -> profileMapper.toAvatarDto(avatar, url)))
                        .map(avatarDto -> UserProfileDto.builder().avatar(avatarDto).build())
                        .defaultIfEmpty(UserProfileDto.builder().build()));
    }

    /**
     * Подтверждает загрузку аватара: валидирует файл в S3, заменяет запись в БД и удаляет старый файл.
     *
     * <p>Удаление старого файла из S3 выполняется после фиксации транзакции в БД,
     * чтобы при сбое сохранения пользователь не потерял аватар. Если удаление старого файла
     * из S3 не удастся — файл останется мусором, но консистентность данных не пострадает.</p>
     */
    @Override
    public Mono<AvatarDto> confirmAvatarUpload(
            UUID actorUserId,
            String objectKey,
            String fileName,
            String contentType) {
        return verifyUserExists(actorUserId)
                .then(Mono.defer(() -> verifyAvatarKeyOwner(actorUserId, objectKey)))
                .then(Mono.defer(() -> storageClient.objectExists(objectKey)))
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.error(new DomainException(
                                DomainStatus.NOT_FOUND, "An object with this key was not found in the storage."));
                    }
                    return Mono.empty();
                })
                .then(Mono.defer(() -> storageClient.validateAndGetUploadedObjectMetadata(objectKey)))
                .flatMap(metadata -> replaceAvatarInDb(actorUserId, objectKey, fileName, contentType, metadata.sizeBytes()))
                .flatMap(savedAvatar -> {
                    String oldKey = savedAvatar.oldObjectKey();
                    Mono<Void> deleteOldFile = oldKey != null
                            ? storageClient.deleteObject(oldKey)
                            .doOnError(e -> log.error("Failed to delete old avatar file from storage: {}", e.getMessage()))
                            .onErrorComplete()
                            : Mono.empty();
                    return deleteOldFile
                            .then(storageClient.createPresignedDownloadUrl(savedAvatar.avatar().getObjectKey())
                                    .map(url -> profileMapper.toAvatarDto(savedAvatar.avatar(), url)));
                });
    }

    /**
     * Атомарно заменяет запись аватара в БД: удаляет старую (если есть) и вставляет новую
     * в рамках одной транзакции. Возвращает ключ старого файла для последующей очистки в S3.
     */
    private Mono<SavedAvatarResult> replaceAvatarInDb(
            UUID userId,
            String objectKey,
            String fileName,
            String contentType,
            long sizeBytes) {
        UserAvatar newAvatar = profileMapper.toUserAvatar(userId, objectKey, fileName, contentType, sizeBytes);
        return transactionalOperator.transactional(
                userAvatarRepository.findByUserId(userId)
                        .flatMap(oldAvatar -> {
                            if (Objects.equals(oldAvatar.getObjectKey(), objectKey)) {
                                return Mono.just(new SavedAvatarResult(oldAvatar, null));
                            }
                            return userAvatarRepository.delete(oldAvatar)
                                    .then(userAvatarRepository.save(newAvatar))
                                    .map(saved -> new SavedAvatarResult(saved, oldAvatar.getObjectKey()));
                        })
                        .switchIfEmpty(Mono.defer(() -> userAvatarRepository.save(newAvatar)
                                .map(saved -> new SavedAvatarResult(saved, null))))
        );
    }

    private Mono<Void> verifyAvatarKeyOwner(UUID userId, String objectKey) {
        String prefix = "avatars/" + userId + "/";
        if (objectKey != null && objectKey.startsWith(prefix)) {
            String suffix = objectKey.substring(prefix.length());
            try {
                if (UUID.fromString(suffix).toString().equals(suffix)) {
                    return Mono.empty();
                }
            } catch (IllegalArgumentException ignored) {
                // Неканонический ключ не считается выданным этому пользователю.
            }
        }
        // Старые ключи без префикса разрешены только для уже сохранённого собственного аватара.
        return userAvatarRepository.findByUserId(userId)
                .filter(avatar -> objectKey != null && objectKey.equals(avatar.getObjectKey()))
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                        "An object with this key was not found in the storage.")))
                .then();
    }

    /**
     * Удаляет запись аватара из БД, затем удаляет файл из хранилища.
     * Операция идемпотентна: если аватар отсутствует, завершается успешно.
     *
     * <p>Удаление из хранилища выполняется после фиксации транзакции в БД.
     * Если удаление файла из хранилища не удастся — файл останется мусором,
     * но консистентность данных не пострадает.</p>
     */
    @Override
    public Mono<Void> deleteMyAvatar(UUID actorUserId) {
        return verifyUserExists(actorUserId)
                .then(Mono.defer(() -> userAvatarRepository.findByUserId(actorUserId)))
                .flatMap(avatar -> userAvatarRepository.delete(avatar)
                        .then(storageClient.deleteObject(avatar.getObjectKey())
                                .doOnError(e -> log.error("Failed to delete avatar file from storage: {}", e.getMessage()))
                                .onErrorComplete()))
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.debug("User {} has no avatar, nothing to delete", actorUserId)));
    }

    /**
     * Возвращает данные пользователей по переданным идентификаторам.
     * Пользователи, которые не найдены в базе данных, не включаются в результат.
     * <p>
     * Алгоритм метода:
     * <li>
     *     Имеет пользователь, который послал запрос, право выполнять запрос
     * </li>
     * <li>
     *     Находится пользователь, выполнивший запрос, в tenant с которого выполняет запрос
     * </li>
     * <li>
     *     Выполняется запрос в grpcTenantServiceClient, после чего проверяется, находятся ли запрошенные пользователи в том же tenant, что и пользователь, выполнивший запрос
     * </li>
     * <li>
     *     Если до, то запрашивается остальная информация и формируется ответ, иначе формируется ошибка RESOURCE_NOT_FOUND
     * </li>
     *
     * <p>
     * Метод автоматически удаляет дубликаты из входного списка {@code userIds} и сохраняет
     * исходный порядок найденных пользователей при возврате результата.
     * Если {@code userIds} равен {@code null} или пуст, возвращает пустой результат.
     */
    @Override
    public Flux<UserDetailsDto> getUserDetailsByIds(List<UUID> userIds) {

        return Flux.deferContextual(context -> {
            if (!context.hasKey(VerifiedTenantContext.class))
                return Mono.error(new DomainException(
                        DomainStatus.UNAUTHENTICATED,
                        "Verified tenant context is required"
                ));

            VerifiedTenantContext verifiedTenantContext = VerifiedTenantContext.require(context);
            String tenantId = verifiedTenantContext.tenantId();
            String actorId = verifiedTenantContext.actorId();

            if (actorId == null || actorId.isBlank() || !verifiedTenantContext.scopes().contains("read")) {
                return Flux.error(new DomainException(
                        DomainStatus.PERMISSION_DENIED,
                        "Tenant directory read access is required"
                ));
            }

            return access.requireActor(UUID.fromString(actorId), false)
                    .flatMapMany(user ->
                            membership.check(user, tenantId)
                                    .thenMany(Flux.defer(() -> {
                                                if (userIds == null || userIds.isEmpty())
                                                    return Flux.empty();
                                                List<UUID> distinctIds = userIds.stream()
                                                        .distinct()
                                                        .toList();
                                                return Flux.fromIterable(distinctIds)
                                                        .buffer(MEMBERSHIP_BATCH_SIZE)
                                                        .concatMap(uuids ->
                                                                        grpcTenantServiceClient.batchValidateMemberships(tenantId, uuids)
                                                                                .switchIfEmpty(Mono.error(new DomainException(
                                                                                        DomainStatus.UNAVAILABLE,
                                                                                        "Tenant service returned no membership batch"
                                                                                )))
                                                                , 0
                                                        )
                                                        .any(membershipBatch ->
                                                                membershipBatch.getItemsList()
                                                                        .stream()
                                                                        .anyMatch(membership -> {
                                                                            var state = membership.getMembership().getState();
                                                                            return !membership.getFound() ||
                                                                                   (state != Membership.StateValue.STATE_ACTIVE &&
                                                                                    state != Membership.StateValue.STATE_SUSPENDED);
                                                                        })
                                                        )
                                                        .flatMapMany(hasForbidden -> {
                                                            if (hasForbidden) {
                                                                return Flux.error(new DomainException(
                                                                        DomainStatus.NOT_FOUND,
                                                                        "Resource not found",
                                                                        Map.of("reason", "RESOURCE_NOT_FOUND")
                                                                ));
                                                            }
                                                            return userRepository.findUsersWithAvatars(distinctIds)
                                                                    .collectMap(UserDetailsDto::userId)
                                                                    .flatMapMany(usersMap -> filterFoundUsersInOrder(usersMap, distinctIds))
                                                                    .flatMapSequential(this::enrichWithAvatarUrl)
                                                                    .doOnError(ex -> log.error("Failed to execute getUserDetailsByIds for userIds: {}", distinctIds, ex))
                                                                    .doOnComplete(() -> log.info("Successfully completed getUserDetailsByIds for requested IDs"));
                                                        });
                                            })
                                    ));
        });
    }

    private Flux<UserDetailsDto> filterFoundUsersInOrder(Map<UUID, UserDetailsDto> usersMap, List<UUID> distinctIds) {
        List<UserDetailsDto> foundList = distinctIds.stream()
                .map(usersMap::get)
                .filter(Objects::nonNull)
                .toList();

        return Flux.fromIterable(foundList);
    }

    private Mono<UserDetailsDto> enrichWithAvatarUrl(UserDetailsDto user) {
        return Mono.justOrEmpty(user.avatar())
                .mapNotNull(AvatarDto::getObjectKey)
                .flatMap(storageClient::createPresignedDownloadUrl)
                .doOnNext(url -> user.avatar().setDownloadUrl(url))
                .doOnError(ex -> log.warn(
                        "Failed to generate presigned URL for userId: {}. Cause: {}",
                        user.userId(), ex.getMessage()
                ))
                .thenReturn(user)
                .onErrorResume(ex -> Mono.just(UserDetailsDto.withoutAvatar(user)));
    }

    private Mono<User> verifyUserExists(UUID userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found: " + userId)));
    }

    /**
     * Результат замены аватара в БД: новая сущность + ключ старого файла для удаления из S3.
     */
    private record SavedAvatarResult(UserAvatar avatar, String oldObjectKey) {
    }
}
