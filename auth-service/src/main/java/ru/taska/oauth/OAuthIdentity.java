package ru.taska.oauth;

/** Verified identity only. Provider tokens are deliberately absent. */
public record OAuthIdentity(String provider, String subject, String email, String suggestedDisplayName) { }
