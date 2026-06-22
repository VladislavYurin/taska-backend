package ru.taska.service;

import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.dto.InviteTokenResponse;

public interface InviteTokenService {
    Mono<InviteTokenResponse> createInviteToken(UUID userId);
}
