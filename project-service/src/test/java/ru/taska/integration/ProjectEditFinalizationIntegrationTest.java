package ru.taska.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.service.ProjectService;
import ru.taska.transport.grpc.GrpcProjectServiceAdapter;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.UpdateProjectRequest;
import ru.taska.api.project.v1.UpdateProjectRequestBody;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ProjectEditFinalizationIntegrationTest extends AbstractIT {
    @Autowired ProjectService service;
    @Autowired ProjectRepository projects;
    @Autowired ProjectMemberRepository members;
    @Autowired GrpcProjectServiceAdapter grpc;
    @Autowired DatabaseClient db;
    private final UUID actor = UUID.randomUUID();

    @Test
    void realCreateWithVersionAndMetadataReadsThroughListAndGet() {
        var p = create();
        assertThat(p.getVersion()).isNotNull();
        assertThat(p.getColor()).isNull();
        assertThat(service.getProject("r", "n", p.getId(), actor).block().description()).isEqualTo("Description");
        assertThat(service.listMyProjects("r", "n", actor).collectList().block()).anySatisfy(item -> {
            assertThat(item.project_id()).isEqualTo(p.getId());
            assertThat(item.description()).isEqualTo("Description");
            assertThat(item.color()).isNull();
        });
        var changed = edit(p, Optional.of("Changed"), Optional.of(" "), true);
        assertThat(changed.getProjectKey()).isEqualTo(p.getProjectKey());
        assertThat(service.getProject("r", "n", p.getId(), actor).block().description()).isNull();
    }

    @Test
    void sameValuesAreNoopAndArchivedProjectRejectsEvenEmptyPatch() {
        var p = create();
        var same = edit(p, Optional.of(p.getName()), Optional.of(p.getDescription()), false);
        assertThat(same.getVersion()).isEqualTo(p.getVersion());
        assertThat(countUpdates(p)).isZero();
        projects.save(p.toBuilder().archivedAt(Instant.now()).build()).block();
        StepVerifier.create(service.updateProject("r", "n", p.getId(), actor, Optional.empty(), Optional.empty(), false, Optional.empty(), false))
                .expectErrorSatisfies(e -> assertThat(((ru.taska.exception.DomainException) e).getDetails())
                        .containsEntry("reason", "PROJECT_ARCHIVED")).verify();
    }

    @Test
    void concurrentPartialUpdatesPreserveBothFields() {
        var p = create();
        var results = Flux.merge(
                service.updateProject("r", "n", p.getId(), actor, Optional.of("Concurrent name"), Optional.empty(), false, Optional.empty(), false),
                service.updateProject("r", "n", p.getId(), actor, Optional.empty(), Optional.of("Concurrent description"), false, Optional.empty(), false))
                .collectList().block();
        assertThat(results).hasSize(2);
        var saved = projects.findById(p.getId()).block();
        assertThat(saved.getName()).isEqualTo("Concurrent name");
        assertThat(saved.getDescription()).isEqualTo("Concurrent description");
        assertThat(countUpdates(p)).isEqualTo(2);
    }

    @Test
    void grpcValidatesFields() {
        var p = create();
        var base = UpdateProjectRequestBody.newBuilder().setProjectId(p.getId().toString()).setActorUserId(actor.toString());
        for (var body : java.util.List.of(base.clone().setName(" ").build(), base.clone().setName("A".repeat(256)).build(),
                base.clone().setDescription("A".repeat(2001)).build(), base.clone().setColor("red").build())) {
            StepVerifier.create(grpc.updateProject(Mono.just(UpdateProjectRequest.newBuilder()
                            .setHeader(Header.newBuilder().setRequestId("r").setNodeId("n")).setBody(body).build())))
                    .expectErrorSatisfies(error -> assertThat(((io.grpc.StatusRuntimeException) error).getStatus().getCode())
                            .isEqualTo(io.grpc.Status.Code.INVALID_ARGUMENT)).verify();
        }
        assertThat(countUpdates(p)).isZero();
    }

    @Test
    void outboxFailureRollsBackProjectUpdate() {
        var p = create();
        db.sql("ALTER TABLE taska.outbox_events ADD CONSTRAINT project_edit_test CHECK (aggregate_id <> '" + p.getId() + "'::uuid) NOT VALID")
                .fetch().rowsUpdated().block();
        try {
            StepVerifier.create(service.updateProject("r", "n", p.getId(), actor, Optional.of("Must rollback"), Optional.empty(), false, Optional.empty(), false))
                    .expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
            assertThat(projects.findById(p.getId()).block().getName()).isEqualTo(p.getName());
            assertThat(countUpdates(p)).isZero();
        } finally {
            db.sql("ALTER TABLE taska.outbox_events DROP CONSTRAINT project_edit_test").fetch().rowsUpdated().block();
        }
    }

    private Project create() {
        return service.createProject("r", "n", "P" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "Project", actor,
                Optional.of("Description"), Optional.empty()).block();
    }

    private Project edit(Project p, Optional<String> name, Optional<String> description, boolean clearColor) {
        return service.updateProject("r", "n", p.getId(), actor, name, description, false, Optional.empty(), clearColor).block().project();
    }

    private long countUpdates(Project p) {
        return db.sql("SELECT count(*) AS n FROM taska.outbox_events WHERE aggregate_id=:id AND event_type=:type")
                .bind("id", p.getId()).bind("type", ru.taska.event.EventType.PROJECT_UPDATED.getValue())
                .map((r, m) -> r.get("n", Long.class)).one().block();
    }
}
