package ru.taska.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.entity.Project;

public interface ProjectService {

    Mono<Project> createProject(String projectKey, String name, String userIdStr);
    Mono<Project> getProject(String projectIdStr);
    Flux<Project> listMyProjects(String userIdStr);
}
