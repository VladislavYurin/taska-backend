package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;

import java.util.UUID;

@Repository
public interface ProjectMemberRepository extends ReactiveCrudRepository<ProjectMember, UUID> {

    Mono<Long> deleteByUserIdAndProjectId(UUID userId, UUID projectId);

    @Modifying
    @Query("UPDATE taska.project_members SET role = :role WHERE user_id = :changedMemberId AND project_id = :projectId")
    Mono<Long> updateRole(UUID changedMemberId, ProjectRole role, UUID projectId);

    Mono<Boolean> existsByUserIdAndProjectId(UUID addedMemberId, UUID projectId);

    Mono<ProjectMember> findByUserIdAndProjectId(UUID userId, UUID projectId);
}
