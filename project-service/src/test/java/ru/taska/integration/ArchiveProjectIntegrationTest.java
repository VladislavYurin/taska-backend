package ru.taska.integration;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectRepository;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.service.ProjectArchiveService;
import ru.taska.service.ProjectService;
import ru.taska.service.ProjectMemberService;
import ru.taska.service.ProjectIssueTypeService;
import ru.taska.service.ProjectWriteGuard;
import ru.taska.transport.kafka.OutboxEventScheduler;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={"spring.grpc.server.port=0", "spring.grpc.server.host=127.0.0.1"})
@Testcontainers
class ArchiveProjectIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.liquibase.url", postgres::getJdbcUrl); r.add("spring.liquibase.user", postgres::getUsername);
        r.add("spring.liquibase.password", postgres::getPassword);
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/"+postgres.getDatabaseName());
        r.add("spring.r2dbc.username", postgres::getUsername); r.add("spring.r2dbc.password", postgres::getPassword);
    }
    @MockitoBean OutboxEventScheduler scheduler;
    @Autowired ru.taska.service.IssueTypeCreationTransactions creations;
    @MockitoBean ru.taska.transport.grpc.client.GrpcAuthServiceClient auth;
    @Autowired ProjectArchiveService archive;
    @Autowired ProjectService service;
    @Autowired ProjectMemberService membership;
    @Autowired ProjectIssueTypeService types;
    @Autowired ProjectRepository projects;
    @Autowired ProjectMemberRepository members;
    @Autowired ProjectWriteGuard guard;
    @Autowired DatabaseClient db;
    private final UUID actor=UUID.randomUUID();

    @Test
    void archiveIsIdempotentKeepsDataReadableAndHidesOnlyFromList() {
        var p=project();
        var one=archive.archive(p.getId(),actor,"r","n").block();
        var two=archive.archive(p.getId(),actor,"retry","n").block();
        assertThat(one.getArchivedAt()).isNotNull();
        assertThat(two.getArchivedAt()).isEqualTo(one.getArchivedAt());
        assertThat(service.getProject("r","n",p.getId(),actor).block().archived_at()).isEqualTo(one.getArchivedAt());
        assertThat(service.listMyProjects("r","n",actor).collectList().block()).isEmpty();
        assertThat(projects.findById(p.getId()).block()).isNotNull();
        assertThat(members.findByUserIdAndProjectId(actor,p.getId()).block()).isNotNull();
        var access=membership.checkProjectMemberRole("r","n",p.getId(),actor).block();
        assertThat(access.projectArchived()).isTrue();
        assertThat(access.isMember()).isTrue();
        assertThat(countEvents(p)).isEqualTo(1);
    }

    @Test
    void concurrentArchivesProduceOneEvent() {
        var p=project();
        assertThat(Flux.merge(archive.archive(p.getId(),actor,"a","n"),archive.archive(p.getId(),actor,"b","n"))
                .collectList().block()).hasSize(2);
        assertThat(countEvents(p)).isEqualTo(1);
    }

    @Test
    void membersAndViewersAndOutsidersCannotArchive() {
        var p=project();
        for (var role:java.util.List.of(ProjectRole.MEMBER,ProjectRole.VIEWER)) {
            UUID other=UUID.randomUUID();
            members.save(ProjectMember.builder().projectId(p.getId()).userId(other).role(role).addedBy(actor).addedAt(Instant.now()).build()).block();
            StepVerifier.create(archive.archive(p.getId(),other,"r","n")).expectErrorSatisfies(this::denied).verify();
        }
        StepVerifier.create(archive.archive(p.getId(),UUID.randomUUID(),"r","n")).expectErrorSatisfies(this::denied).verify();
        assertThat(projects.findById(p.getId()).block().getArchivedAt()).isNull();
        assertThat(countEvents(p)).isZero();
    }

    @Test
    void projectLocalWritesRejectArchiveButTypeMetadataRemainsReadable() {
        var p=project();
        archive.archive(p.getId(),actor,"r","n").block();
        StepVerifier.create(guard.check(p.getId(),actor)).expectErrorSatisfies(this::archived).verify();
        StepVerifier.create(membership.rmProjectMember("r","n",actor,actor,p.getId())).expectErrorSatisfies(this::archived).verify();
        StepVerifier.create(creations.begin(p.getId(),actor,"archive-request","n","REQUEST","Request","blue","flag",null,1,null,ru.taska.domain.ActionMetadata.NONE)).expectErrorSatisfies(this::archived).verify();
        // A project without seeded registry has a valid empty read; archive itself does not forbid it.
        StepVerifier.create(types.list(p.getId(),actor,"r","n")).verifyComplete();
    }

    @Test
    void outboxFailureRollsBackArchive() {
        var p=project();
        db.sql("ALTER TABLE taska.outbox_events ADD CONSTRAINT archive_test CHECK (aggregate_id <> '"+p.getId()+"'::uuid) NOT VALID")
                .fetch().rowsUpdated().block();
        try {
            StepVerifier.create(archive.archive(p.getId(),actor,"r","n"))
                    .expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
            assertThat(projects.findById(p.getId()).block().getArchivedAt()).isNull();
        } finally { db.sql("ALTER TABLE taska.outbox_events DROP CONSTRAINT archive_test").fetch().rowsUpdated().block(); }
    }

    @Test
    void adminRevocationWaitsUntilAuthorizedMemberAdditionCompletes() throws Exception {
        var p = project();
        UUID secondAdmin = UUID.randomUUID(), addedUser = UUID.randomUUID();
        members.save(ProjectMember.builder().projectId(p.getId()).userId(secondAdmin).role(ProjectRole.ADMIN)
                .addedBy(actor).addedAt(Instant.now()).build()).block();
        var lookupStarted = new java.util.concurrent.CountDownLatch(1);
        var lookup = reactor.core.publisher.Sinks.<ru.taska.api.auth.profile.v1.GetUserDetailsByIdsResponse>one();
        org.mockito.Mockito.when(auth.getUserDetailsByIds(java.util.List.of(addedUser), "add", "n"))
                .thenReturn(Mono.defer(() -> { lookupStarted.countDown(); return lookup.asMono(); }));
        var addition = membership.addProjectMember("add", "n", addedUser, actor, ProjectRole.MEMBER, p.getId()).toFuture();
        assertThat(lookupStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var revocation = membership.changeProjectMemberRole("revoke", "n", actor, secondAdmin, ProjectRole.MEMBER, p.getId());
        try {
            StepVerifier.create(revocation).expectSubscription().expectNoEvent(java.time.Duration.ofMillis(200))
                    .then(() -> lookup.tryEmitValue(ru.taska.api.auth.profile.v1.GetUserDetailsByIdsResponse.newBuilder()
                            .addUserDetails(ru.taska.api.auth.profile.v1.UserDetails.newBuilder().setUserId(addedUser.toString())).build()))
                    .assertNext(member -> assertThat(member.getRole()).isEqualTo(ProjectRole.MEMBER)).verifyComplete();
            assertThat(addition.get(5, java.util.concurrent.TimeUnit.SECONDS).getUserId()).isEqualTo(addedUser);
        } finally {
            lookup.tryEmitError(new IllegalStateException("Test cleanup"));
            addition.cancel(true);
        }
    }

    @Test
    void projectListHasStableCreationAndIdOrderAndExcludesArchivedProjects() {
        var first = project();
        var second = project();
        var older = project();
        var hidden = project();
        db.sql("UPDATE taska.projects SET created_at='2026-01-02T00:00:00Z' WHERE id IN (:one,:two)")
                .bind("one", first.getId()).bind("two", second.getId()).fetch().rowsUpdated().block();
        db.sql("UPDATE taska.projects SET created_at='2026-01-01T00:00:00Z' WHERE id=:id")
                .bind("id", older.getId()).fetch().rowsUpdated().block();
        archive.archive(hidden.getId(), actor, "archive", "n").block();
        var tied = java.util.stream.Stream.of(first.getId(), second.getId())
                .sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        var expected = java.util.List.of(older.getId(), tied.get(0), tied.get(1));
        for (int read = 0; read < 2; read++) {
            assertThat(service.listMyProjects("r", "n", actor).map(item -> item.project_id()).collectList().block())
                    .containsExactlyElementsOf(expected);
        }
        assertThat(service.listMyProjects("r", "n", UUID.randomUUID()).collectList().block()).isEmpty();
    }

    private Project project() {
        var p=projects.save(Project.builder().name("Business").projectKey("P"+UUID.randomUUID().toString().substring(0,8)).createdBy(actor).build()).block();
        members.save(ProjectMember.builder().projectId(p.getId()).userId(actor).role(ProjectRole.ADMIN).addedBy(actor).addedAt(Instant.now()).build()).block();
        return p;
    }
    private long countEvents(Project p) { return db.sql("SELECT count(*) AS n FROM taska.outbox_events WHERE aggregate_id=:id")
            .bind("id",p.getId()).map((r,m)->r.get("n",Long.class)).one().block(); }
    private void denied(Throwable e) { assertThat(((DomainException)e).getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED); }
    private void archived(Throwable e) { assertThat(((DomainException)e).getDetails()).containsEntry("reason","PROJECT_ARCHIVED"); }
}
