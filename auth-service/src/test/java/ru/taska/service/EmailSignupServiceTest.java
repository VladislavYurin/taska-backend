package ru.taska.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import ru.taska.config.props.SignupProperties;
import ru.taska.exception.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailSignupServiceTest {
    @Test void disabledSignupDoesNotReserveIpOrTouchAccountAndMethodsHideUnknownProviders() {
        var props=new SignupProperties();
        var limiter=mock(SignupIpLimiter.class);var tx=mock(EmailSignupTransaction.class);
        var beans=new DefaultListableBeanFactory();
        beans.registerSingleton("providers",(AuthProviderCatalog) () -> java.util.List.of("APPLE","UNKNOWN","GOOGLE","GOOGLE"));
        var service=new EmailSignupService(props,mock(ru.taska.util.DirectoryInput.class),limiter,tx,beans.getBeanProvider(AuthProviderCatalog.class));
        assertThatThrownBy(() -> service.signup("x@example.test","Name","192.0.2.1","req").block())
                .isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getDetails()).containsEntry("reason","SIGNUP_DISABLED"));
        verifyNoInteractions(limiter,tx);
        assertThat(service.enabled()).isFalse();
        assertThat(service.providers()).containsExactly("GOOGLE","APPLE");
    }
}
