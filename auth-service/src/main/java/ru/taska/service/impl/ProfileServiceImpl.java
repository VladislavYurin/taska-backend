package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.dto.AvatarDto;
import ru.taska.dto.UserProfileDto;
import ru.taska.entity.User;
import ru.taska.entity.UserAvatar;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProfileMapper;
import ru.taska.repository.UserAvatarRepository;
import ru.taska.repository.UserRepository;
import ru.taska.service.ProfileService;
import ru.taska.storage.client.StorageClient;
import ru.taska.storage.dto.PresignedUploadResult;

import java.util.Optional;
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

    @Override
    public Mono<String> getAvatarDownloadUrl(UUID actorUserId) {
        return verifyUserExists(actorUserId)
                .then(Mono.defer(() -> userAvatarRepository.findByUserId(actorUserId)))
                .flatMap(avatar -> storageClient.createPresignedDownloadUrl(avatar.getObjectKey()));
    }

    @Override
    public Mono<PresignedUploadResult> createAvatarUploadUrl(UUID actorUserId, String contentType, long sizeBytes) {
        return verifyUserExists(actorUserId)
                .then(Mono.defer(() -> storageClient.createPresignedUploadUrl(contentType, sizeBytes)));
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
                        .flatMap(oldAvatar -> userAvatarRepository.delete(oldAvatar)
                                .thenReturn(Optional.of(oldAvatar.getObjectKey())))
                        .defaultIfEmpty(Optional.empty())
                        .flatMap(oldKey -> userAvatarRepository.save(newAvatar)
                                .map(saved -> new SavedAvatarResult(saved, oldKey.orElse(null))))
        );
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
