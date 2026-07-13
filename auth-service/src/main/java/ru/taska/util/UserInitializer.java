package ru.taska.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.Credential;
import ru.taska.entity.CredentialType;
import ru.taska.entity.HashingAlgorithm;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.PasswordHashService;

/**
 * Класс для иициализации пользователя, выполняется при старте приложения.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Profile("!test")
public class UserInitializer implements ApplicationRunner {

    @Value("${admin.password}")
    private String adminPass;

    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordHashService passwordHashService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        userRepository.existsByLogin("admin")
                      .flatMap(exists -> {
                          if (exists) {
                              log.info("Admin user already exists, skipping initialization");
                              return Mono.empty();
                          }
                          return userRepository.save(
                                                       User.builder()
                                                           .login("admin")
                                                           .globalRole(GlobalRole.GLOBAL_ADMIN)
                                                           .email("admin@adminov.ru")
                                                           .displayName("admin")
                                                           .status(UserStatus.ACTIVE)
                                                           .build())
                                               .flatMap(user -> credentialRepository.save(
                                                       Credential.builder()
                                                                 .userId(user.getId())
                                                                 .credentialType(CredentialType.PASSWORD)
                                                                 .secretHash(passwordHashService.encode(
                                                                         adminPass,
                                                                         HashingAlgorithm.BCRYPT
                                                                 ))
                                                                 .algo(HashingAlgorithm.BCRYPT)
                                                                 .failedAttempts(0)
                                                                 .build()))
                                               .flatMap(cred -> passwordHashService.matches(
                                                                                           cred,
                                                                                           adminPass
                                                                                   )
                                                                                   .doOnNext(matches -> log.info(
                                                                                           "Admin user created. Password matches: {}",
                                                                                           matches
                                                                                   )));
                      }).block();
        userRepository.existsByLogin("defaultUser")
                      .flatMap(exists -> {
                          if (exists) {
                              log.info(
                                      "DefaultUser user already exists, skipping initialization");
                              return Mono.empty();
                          }
                          return userRepository.save(
                                                       User.builder()
                                                           .login("defaultUser")
                                                           .globalRole(GlobalRole.USER)
                                                           .email("defaultuser@mail.ru")
                                                           .displayName("defaultUser")
                                                           .status(UserStatus.ACTIVE)
                                                           .build())
                                               .flatMap(user -> credentialRepository.save(
                                                       Credential.builder()
                                                                 .userId(user.getId())
                                                                 .credentialType(CredentialType.PASSWORD)
                                                                 .secretHash(passwordHashService.encode(
                                                                         "defaultPassword",
                                                                         HashingAlgorithm.BCRYPT
                                                                 ))
                                                                 .algo(HashingAlgorithm.BCRYPT)
                                                                 .failedAttempts(0)
                                                                 .build()))
                                               .flatMap(cred -> passwordHashService.matches(
                                                                                           cred,
                                                                                           "defaultPassword"
                                                                                   )
                                                                                   .doOnNext(
                                                                                           matches -> log.info(
                                                                                                   "defaultUser user created. Password matches: {}",
                                                                                                   matches
                                                                                           )));
                      })
                .block();
    }

}