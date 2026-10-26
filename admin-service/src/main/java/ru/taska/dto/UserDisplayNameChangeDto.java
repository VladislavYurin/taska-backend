package ru.taska.dto;

import java.util.UUID;

public record UserDisplayNameChangeDto(UUID userId, String previousDisplayName, String displayName) { }
