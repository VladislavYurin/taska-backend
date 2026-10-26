package ru.taska;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.auth.v1.*;
import ru.taska.api.common.v1.AccessTokenKind;
import ru.taska.api.common.v1.Header;
import ru.taska.domain.GlobalRole;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.UserRepository;
import ru.taska.security.JwtService;
import ru.taska.security.pat.PersonalAccessTokenService;
import ru.taska.service.AdminUserManagementService;
import ru.taska.service.AuthService;
import ru.taska.transport.kafka.OutboxEventScheduler;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties="personal-tokens.max-active-tokens=2")
class PersonalAccessTokenIT extends AbstractIT {
    @MockitoBean OutboxEventScheduler outboxScheduler;
    @Autowired UserRepository users;
    @Autowired ru.taska.repository.InviteTokenRepository invites;
    @Autowired DatabaseClient db;
    @Autowired PersonalAccessTokenService tokens;
    @Autowired AuthService authentication;
    @Autowired AdminUserManagementService administration;
    @Autowired JwtService jwt;
    @Autowired GrpcServerLifecycle server;
    private User owner;
    private String session;
    private ManagedChannel channel;
    private ReactorPersonalAccessTokenServiceGrpc.ReactorPersonalAccessTokenServiceStub rpc;
    private ReactorAuthServiceGrpc.ReactorAuthServiceStub authRpc;
    private static final Duration TIMEOUT=Duration.ofSeconds(15);
    private static final Header HEADER=Header.newBuilder().setRequestId("pat-it").setNodeId("gateway").build();

    @BeforeEach void prepare() {
        owner=users.save(User.builder().email("pat-"+UUID.randomUUID()+"@example.test").displayName("PAT owner")
                .status(UserStatus.ACTIVE).globalRole(GlobalRole.USER).build()).block(TIMEOUT);
        session=jwt.generateAccessToken(owner).block(TIMEOUT);
        channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        rpc=ReactorPersonalAccessTokenServiceGrpc.newReactorStub(channel);
        authRpc=ReactorAuthServiceGrpc.newReactorStub(channel);
    }
    @AfterEach void close() { channel.shutdownNow(); }

    @Test void rpcLifecyclePreservesPrivacyAndRejectsOldClientsAndPatManagement(CapturedOutput output) {
        var issued=rpc.createPersonalAccessToken(Mono.just(CreatePersonalAccessTokenRequest.newBuilder().setHeader(HEADER)
                .setSessionAccessToken(session).setName(" Laptop ").addScopes("read").build())).block(TIMEOUT);
        var secret=issued.getSecret(); var id=issued.getToken().getId();
        assertThat(secret).matches("tsk_pat_[A-Za-z0-9_-]{43}");
        assertThat(issued.getToken().getName()).isEqualTo("Laptop");
        var request=ValidateAccessTokenRequest.newBuilder().setHeader(HEADER)
                .setBody(ValidateAccessTokenRequestBody.newBuilder().setAccessToken(secret)).build();
        assertThatThrownBy(()->authRpc.validateAccessToken(Mono.just(request)).block(TIMEOUT))
                .matches(e->Status.fromThrowable(e).getCode()==Status.Code.UNAUTHENTICATED);
        var context=authRpc.validateAccessToken(Mono.just(request.toBuilder()
                .setBody(request.getBody().toBuilder().setAllowPersonalToken(true)).build())).block(TIMEOUT).getUserContext();
        assertThat(context.getTokenKind()).isEqualTo(AccessTokenKind.ACCESS_TOKEN_KIND_PAT);
        assertThat(context.getTokenId()).isEqualTo(id);
        assertThat(context.getScopesList()).containsExactly("read");
        var list=rpc.listPersonalAccessTokens(Mono.just(ListPersonalAccessTokensRequest.newBuilder()
                .setHeader(HEADER).setSessionAccessToken(session).build())).block(TIMEOUT);
        assertThat(list.getItemsCount()).isEqualTo(1);
        assertThat(list.getMaxActiveTokens()).isEqualTo(2);
        assertThat(list.toString()).doesNotContain(secret,DigestUtils.sha256Hex(secret));
        assertThatThrownBy(()->rpc.listPersonalAccessTokens(Mono.just(ListPersonalAccessTokensRequest.newBuilder()
                .setHeader(HEADER).setSessionAccessToken(secret).build())).block(TIMEOUT))
                .matches(e->Status.fromThrowable(e).getCode()==Status.Code.PERMISSION_DENIED);
        assertThatThrownBy(()->rpc.createPersonalAccessToken(Mono.just(CreatePersonalAccessTokenRequest.newBuilder()
                .setHeader(HEADER).setSessionAccessToken(secret).setName("Escalation").addScopes("read").build())).block(TIMEOUT))
                .matches(e->Status.fromThrowable(e).getCode()==Status.Code.PERMISSION_DENIED);
        assertThatThrownBy(()->rpc.revokePersonalAccessToken(Mono.just(RevokePersonalAccessTokenRequest.newBuilder()
                .setHeader(HEADER).setSessionAccessToken(secret).setTokenId(id).build())).block(TIMEOUT))
                .matches(e->Status.fromThrowable(e).getCode()==Status.Code.PERMISSION_DENIED);
        var revoke=RevokePersonalAccessTokenRequest.newBuilder().setHeader(HEADER).setSessionAccessToken(session).setTokenId(id).build();
        rpc.revokePersonalAccessToken(Mono.just(revoke)).block(TIMEOUT);
        rpc.revokePersonalAccessToken(Mono.just(revoke)).block(TIMEOUT);
        assertInvalid(secret);
        assertThat(scalar("SELECT count(*) FROM taska.outbox_events WHERE aggregate_id=:id AND event_type='PersonalAccessTokenRevoked'",owner.getId())).isEqualTo(1);
        var events=db.sql("SELECT payload::text AS body FROM taska.outbox_events WHERE aggregate_id=:id")
                .bind("id",owner.getId()).map((r,m)->r.get("body",String.class)).all().collectList().block(TIMEOUT);
        assertThat(events).allSatisfy(body->assertThat(body).doesNotContain(secret,DigestUtils.sha256Hex(secret)));
        assertThat(output.getAll()).doesNotContain(secret,DigestUtils.sha256Hex(secret));
    }

    @Test void activeLimitIsAtomicForConcurrentRequestsAndDuplicateNamesAreAllowed() {
        var results=Flux.range(0,3).flatMap(i->tokens.create(owner.getId(),"Same name",List.of("read"),1,"race")
                .subscribeOn(Schedulers.parallel()).map(token->true).onErrorResume(DomainException.class,error->{
                    assertThat(error.getStatus()).isEqualTo(DomainStatus.FAILED_PRECONDITION);
                    assertThat(error.getDetails()).containsEntry("reason","ACTIVE_TOKEN_LIMIT");return Mono.just(false);
                }),3).collectList().block(TIMEOUT);
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(2);
        assertThat(tokens.list(owner.getId()).collectList().block(TIMEOUT)).hasSize(2);
        assertThat(scalar("SELECT count(*) FROM taska.outbox_events WHERE aggregate_id=:id",owner.getId())).isEqualTo(2);
    }

    @Test void foreignRevokeIsHiddenAndLastUsedWritesAreThrottled() {
        var issued=tokens.create(owner.getId(),"Used",List.of("read","write"),1,"r").block(TIMEOUT);
        var other=users.save(User.builder().email(UUID.randomUUID()+"@example.test").displayName("Other")
                .status(UserStatus.ACTIVE).build()).block(TIMEOUT);
        tokens.revoke(other.getId(),issued.token().getId(),"foreign").block(TIMEOUT);
        authentication.validateAccessToken(issued.secret()).block(TIMEOUT);
        var before=rowVersion(issued.token().getId());
        authentication.validateAccessToken(issued.secret()).block(TIMEOUT);
        assertThat(rowVersion(issued.token().getId())).isEqualTo(before);
        assertThat(tokens.list(other.getId()).collectList().block(TIMEOUT)).isEmpty();
    }

    @Test void expirationAndInactiveStatusesRejectAndDeletionCascades() {
        var issued=tokens.create(owner.getId(),"Status",List.of("read"),1,"r").block(TIMEOUT);
        for(var status:List.of(UserStatus.LOCKED,UserStatus.INVITED,UserStatus.BLOCKED)) {
            owner.setStatus(status);users.save(owner).block(TIMEOUT);assertInvalid(issued.secret());
        }
        owner.setStatus(UserStatus.ACTIVE);users.save(owner).block(TIMEOUT);
        db.sql("UPDATE taska.personal_access_tokens SET created_at=now()-interval '2 hours',expires_at=now()-interval '1 hour' WHERE id=:id")
                .bind("id",issued.token().getId()).fetch().rowsUpdated().block(TIMEOUT);
        assertInvalid(issued.secret());
        var replacement=tokens.create(owner.getId(),"Status",List.of("read"),1,"r").block(TIMEOUT);
        users.deleteById(owner.getId()).block(TIMEOUT);
        assertInvalid(replacement.secret());
        assertThat(scalar("SELECT count(*) FROM taska.personal_access_tokens WHERE user_id=:id",owner.getId())).isZero();
    }

    @Test void blockAndUnblockCannotReviveAnOldTokenAndJwtStillValidates() {
        var issued=tokens.create(owner.getId(),"Block",List.of("read"),1,"r").block(TIMEOUT);
        var request=UserStatusRequestDto.builder().targetUserId(owner.getId()).actorUserId(owner.getId()).reason("IT").build();
        administration.blockUser("block","it",request).block(TIMEOUT);
        assertInvalid(issued.secret());
        administration.unblockUser("unblock","it",request).block(TIMEOUT);
        assertInvalid(issued.secret());
        assertThat(authentication.validateAccessToken(session).block(TIMEOUT).getTokenKind())
                .isEqualTo(AccessTokenKind.ACCESS_TOKEN_KIND_JWT);
    }

    @Test void passwordActivationPermanentlyRevokesPreexistingTokens() {
        var first=tokens.create(owner.getId(),"Before reset",List.of("read"),1,"r").block(TIMEOUT);
        var second=tokens.create(owner.getId(),"Before reset",List.of("read","write"),1,"r").block(TIMEOUT);
        owner.setStatus(UserStatus.INVITED);users.save(owner).block(TIMEOUT);
        String invitation=UUID.randomUUID().toString();
        invites.save(ru.taska.entity.InviteToken.builder().userId(owner.getId()).tokenHash(DigestUtils.sha256Hex(invitation))
                .expiresAt(Instant.now().plusSeconds(600)).build()).block(TIMEOUT);
        authentication.setPasswordByToken("password-reset",invitation,"Password123!Strong").block(TIMEOUT);
        assertThat(users.findById(owner.getId()).block(TIMEOUT).getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertInvalid(first.secret());assertInvalid(second.secret());
        assertThat(tokens.list(owner.getId()).collectList().block(TIMEOUT)).isEmpty();
        assertThat(scalar("SELECT count(*) FROM taska.outbox_events WHERE aggregate_id=:id AND event_type='PersonalAccessTokenRevoked' AND payload->>'reason'='PASSWORD_CHANGED'",owner.getId())).isEqualTo(2);
    }

    @Test void outboxFailureRollsBackIssueAndRevoke() {
        var issued=tokens.create(owner.getId(),"Atomic",List.of("read"),1,"r").block(TIMEOUT);
        db.sql("ALTER TABLE taska.outbox_events ADD CONSTRAINT pat_atomic_test CHECK(event_type NOT LIKE 'PersonalAccessToken%') NOT VALID")
                .fetch().rowsUpdated().block(TIMEOUT);
        try {
            assertThatThrownBy(()->tokens.create(owner.getId(),"Rollback",List.of("read"),1,"r").block(TIMEOUT))
                    .rootCause().hasMessageContaining("pat_atomic_test");
            assertThat(tokens.list(owner.getId()).collectList().block(TIMEOUT)).hasSize(1);
            assertThatThrownBy(()->tokens.revoke(owner.getId(),issued.token().getId(),"r").block(TIMEOUT))
                    .rootCause().hasMessageContaining("pat_atomic_test");
            assertThat(authentication.validateAccessToken(issued.secret()).block(TIMEOUT).getTokenId()).isEqualTo(issued.token().getId().toString());
        } finally {
            db.sql("ALTER TABLE taska.outbox_events DROP CONSTRAINT pat_atomic_test").fetch().rowsUpdated().block(TIMEOUT);
        }
    }

    private String rowVersion(UUID id) {
        return db.sql("SELECT xmin::text AS v FROM taska.personal_access_tokens WHERE id=:id").bind("id",id)
                .map((r,m)->r.get("v",String.class)).one().block(TIMEOUT);
    }
    private long scalar(String sql, UUID id) {return db.sql(sql).bind("id",id).map((r,m)->r.get(0,Long.class)).one().block(TIMEOUT);}
    private void assertInvalid(String secret) {
        assertThatThrownBy(()->authentication.validateAccessToken(secret).block(TIMEOUT))
                .isInstanceOf(DomainException.class).matches(e->((DomainException)e).getStatus()==DomainStatus.UNAUTHENTICATED);
    }
}
