package ru.taska.projectbootstrap;

import org.springframework.context.annotation.*;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/** The deployed pre-tenant API. Never load these repositories/jobs against tenant storage. */
@Configuration
@Profile("!tenant-storage")
@ComponentScan({"ru.taska.config", "ru.taska.service", "ru.taska.repository", "ru.taska.transport", "ru.taska.mapper"})
@EnableR2dbcRepositories("ru.taska.repository")
public class LegacyProjectConfiguration {}
