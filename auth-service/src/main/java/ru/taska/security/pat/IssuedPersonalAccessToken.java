package ru.taska.security.pat;

public record IssuedPersonalAccessToken(PersonalAccessToken token, String secret) {
    @Override public String toString() { return "IssuedPersonalAccessToken[tokenId=" + token.getId() + "]"; }
}
