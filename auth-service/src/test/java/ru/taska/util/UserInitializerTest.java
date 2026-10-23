package ru.taska.util;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.PasswordHashService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserInitializerTest {
    @Test
    void repeatedStartupRecognizesSeedAccountsByEmail() throws Exception {
        UserRepository users = mock(UserRepository.class);
        CredentialRepository credentials = mock(CredentialRepository.class);
        PasswordHashService passwords = mock(PasswordHashService.class);
        when(users.existsByEmail("admin@adminov.ru")).thenReturn(Mono.just(true));
        when(users.existsByEmail("defaultuser@mail.ru")).thenReturn(Mono.just(true));
        UserInitializer initializer = new UserInitializer(users, credentials, passwords);

        initializer.run(null);
        initializer.run(null);

        verify(users, times(2)).existsByEmail("admin@adminov.ru");
        verify(users, times(2)).existsByEmail("defaultuser@mail.ru");
        verify(users, never()).save(any());
        verifyNoInteractions(credentials, passwords);
    }
}
