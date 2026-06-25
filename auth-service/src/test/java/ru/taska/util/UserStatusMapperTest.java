package ru.taska.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.taska.api.common.v1.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserStatusMapper Tests")
class UserStatusMapperTest {

    @Test
    @DisplayName("Should map INVITED entity to proto INVITED")
    void shouldMapInvitedEntityToProto() {
        // When
        UserStatus result = UserStatusMapper.toProtoStatus(ru.taska.entity.UserStatus.INVITED);

        // Then
        assertThat(result).isEqualTo(UserStatus.USER_STATUS_INVITED);
    }

    @Test
    @DisplayName("Should map ACTIVE entity to proto ACTIVE")
    void shouldMapActiveEntityToProto() {
        // When
        UserStatus result = UserStatusMapper.toProtoStatus(ru.taska.entity.UserStatus.ACTIVE);

        // Then
        assertThat(result).isEqualTo(UserStatus.USER_STATUS_ACTIVE);
    }

    @Test
    @DisplayName("Should map BLOCKED entity to proto BLOCKED")
    void shouldMapBlockedEntityToProto() {
        // When
        UserStatus result = UserStatusMapper.toProtoStatus(ru.taska.entity.UserStatus.BLOCKED);

        // Then
        assertThat(result).isEqualTo(UserStatus.USER_STATUS_BLOCKED);
    }

    @Test
    @DisplayName("Should map ACTIVE proto to entity ACTIVE")
    void shouldMapActiveProtoToEntity() {
        // When
        ru.taska.entity.UserStatus result = UserStatusMapper.toEntityStatus(UserStatus.USER_STATUS_ACTIVE);

        // Then
        assertThat(result).isEqualTo(ru.taska.entity.UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("Should map BLOCKED proto to entity BLOCKED")
    void shouldMapBlockedProtoToEntity() {
        // When
        ru.taska.entity.UserStatus result = UserStatusMapper.toEntityStatus(UserStatus.USER_STATUS_BLOCKED);

        // Then
        assertThat(result).isEqualTo(ru.taska.entity.UserStatus.BLOCKED);
    }

    @Test
    @DisplayName("Should map UNSPECIFIED proto to INVITED entity (default)")
    void shouldMapUnspecifiedProtoToInvitedEntity() {
        // When
        ru.taska.entity.UserStatus result = UserStatusMapper.toEntityStatus(UserStatus.USER_STATUS_UNSPECIFIED);

        // Then
        assertThat(result).isEqualTo(ru.taska.entity.UserStatus.INVITED);
    }

    @Test
    @DisplayName("Should map INVITED proto to INVITED entity")
    void shouldMapInvitedProtoToEntity() {
        // When
        ru.taska.entity.UserStatus result = UserStatusMapper.toEntityStatus(UserStatus.USER_STATUS_INVITED);

        // Then
        assertThat(result).isEqualTo(ru.taska.entity.UserStatus.INVITED);
    }
}