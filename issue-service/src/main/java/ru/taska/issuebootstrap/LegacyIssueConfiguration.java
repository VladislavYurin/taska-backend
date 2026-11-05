package ru.taska.issuebootstrap;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@Profile("!tenant-storage")
@ComponentScan({
  "ru.taska.config",
  "ru.taska.service",
  "ru.taska.repository",
  "ru.taska.transport",
  "ru.taska.mapper",
  "ru.taska.util"
})
@EnableR2dbcRepositories("ru.taska.repository")
@EnableScheduling
public class LegacyIssueConfiguration {}
