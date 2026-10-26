package ru.taska.util;

import ru.taska.api.common.v1.UserContext;
import ru.taska.entity.User;

public final class UserContexts {
    private UserContexts() {}
    public static UserContext.Builder builder(User user) {
        return UserContext.newBuilder().setUserId(user.getId().toString()).setEmail(user.getEmail())
                .setDisplayName(user.getDisplayName()).setStatus(UserStatusMapper.toProtoStatus(user.getStatus()))
                .setGlobalRole(GlobalRoleMapper.toProtoGlobalRole(user.getGlobalRole()));
    }
}
