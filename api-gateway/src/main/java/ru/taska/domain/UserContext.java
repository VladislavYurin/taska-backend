package ru.taska.domain;

// пока содержит только sub - нет контракта с auth-service
// грубо говоря, неизвестно, что будет в payload токена точно.
public record UserContext(String userId) {

}
