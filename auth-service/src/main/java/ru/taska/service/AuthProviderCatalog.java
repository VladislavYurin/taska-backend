package ru.taska.service;

import java.util.List;

/** Configured, enabled external providers; never contains client secrets. */
public interface AuthProviderCatalog {
    List<String> enabledProviders();
}
