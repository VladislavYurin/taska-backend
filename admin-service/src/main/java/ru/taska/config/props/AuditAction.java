package ru.taska.config.props;

/**
 * Классификация операций для аудита
 */
public enum AuditAction {
    USER_BLOCKED,
    USER_UNBLOCKED,
    RESET_CREDENTIAL_LOCKOUT
}
