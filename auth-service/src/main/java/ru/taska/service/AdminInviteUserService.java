package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequest;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserResponse;

public interface AdminInviteUserService {
    Mono<InviteUserResponse> inviteUser(InviteUserRequest request);
}
