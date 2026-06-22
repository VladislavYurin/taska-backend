package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.api.auth.admin.inviteuser.v1.AdminCreateUserRequest;
import ru.taska.api.auth.admin.inviteuser.v1.UserCreatedResponse;

public interface AdminInviteUserService {
    Mono<UserCreatedResponse> inviteUser(AdminCreateUserRequest request);
}
