package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.ProjectMemberDto;
import ru.taska.domain.dto.ProjectMemberStatsRow;

import java.util.Collection;
import java.util.UUID;

@Repository
public interface ProjectMemberRepository extends ReactiveCrudRepository<ProjectMember, UUID> {
    @Query("SELECT EXISTS(SELECT 1 FROM taska.project_members WHERE user_id=:userId)")
    Mono<Boolean> hasAnyMembership(UUID userId);

    @Query("SELECT * FROM taska.project_members WHERE project_id=:projectId AND user_id=:userId FOR SHARE")
    Mono<ProjectMember> findByUserIdAndProjectIdForShare(UUID userId, UUID projectId);


    @Query("""
        SELECT project_id, user_id, role, added_at, member_count
        FROM (
            SELECT pm.*, count(*) OVER (PARTITION BY pm.project_id) AS member_count,
                   row_number() OVER (PARTITION BY pm.project_id ORDER BY pm.added_at, pm.user_id) AS position
            FROM taska.project_members pm
            WHERE pm.project_id IN (:projectIds)
              AND EXISTS (SELECT 1 FROM taska.project_members requester
                          WHERE requester.project_id = pm.project_id AND requester.user_id = :actorUserId)
        ) ranked
        WHERE position <= 5
        ORDER BY project_id, added_at, user_id
        """)
    Flux<ProjectMemberStatsRow> findMemberStatsBatch(
            Collection<UUID> projectIds, UUID actorUserId);

    Mono<Long> deleteByUserIdAndProjectId(UUID userId, UUID projectId);

    @Modifying
    @Query("UPDATE taska.project_members SET role = :role WHERE user_id = :changedMemberId AND project_id = :projectId")
    Mono<Long> updateRole(UUID changedMemberId, ProjectRole role, UUID projectId);

    @Query("""
    SELECT user_id, role, added_at
    FROM taska.project_members
    WHERE project_id = :projectId
        AND (role = 'ADMIN' OR user_id = :actorUserId OR user_id = :changedMemberId)
    ORDER BY user_id
    FOR UPDATE
    """)
    Flux<ProjectMemberDto> getRequiredMembersInProject(UUID actorUserId, UUID changedMemberId, UUID projectId);

    @Query("SELECT user_id, role FROM taska.project_members WHERE project_id = :projectId " +
            "AND (role = 'ADMIN' OR user_id = :actorUserId OR user_id = :addedMemberId)")
    Flux<ProjectMemberDto> getRequiredMembersInProjectNoLock(UUID actorUserId, UUID addedMemberId, UUID projectId);

    Mono<ProjectMember> findByUserIdAndProjectId(UUID userId, UUID projectId);

    @Query("""
    SELECT pm.user_id, pm.role, pm.added_at
    FROM taska.project_members pm
    WHERE pm.project_id = :projectId
      AND EXISTS (
          SELECT 1
          FROM taska.project_members requester
          WHERE requester.project_id = :projectId
            AND requester.user_id = :actorUserId
      )
    ORDER BY pm.user_id
    """)
    Flux<ProjectMemberDto> findProjectMembers(UUID projectId, UUID actorUserId);
}
