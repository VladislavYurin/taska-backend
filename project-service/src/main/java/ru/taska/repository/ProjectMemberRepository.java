package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import ru.taska.entity.ProjectMember;

import java.util.UUID;

@Repository
public interface ProjectMemberRepository extends ReactiveCrudRepository<ProjectMember, UUID> {
}
