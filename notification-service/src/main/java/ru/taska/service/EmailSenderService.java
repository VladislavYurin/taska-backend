package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.Notification;

public interface EmailSenderService {

    Mono<Void> sendIfEnabled(Notification notification);

}
