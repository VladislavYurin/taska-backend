package ru.taska.oauth;

import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import ru.taska.AbstractIT;
import ru.taska.api.auth.oauth.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.entity.*;
import ru.taska.exception.DomainException;
import ru.taska.repository.UserRepository;
import ru.taska.transport.kafka.OutboxEventScheduler;
import security.OAuthServiceCredentials;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "taska.signup.enabled=true", "oauth-bridge.enabled=true", "oauth-bridge.issuer=https://oauth.example.test",
    "oauth-bridge.resource=https://mcp.example.test/mcp", "oauth-bridge.introspection-uri=http://127.0.0.1:1/introspect",
    "oauth-bridge.grant-validation-uri=http://127.0.0.1:1/validate",
    "oauth-bridge.introspection-secret=introspection-447-test-000000000000000000000001",
    "oauth-bridge.identity-service-credential=identity-447-test-000000000000000000000000001",
    "oauth-bridge.mcp-exchange-credential=exchange-447-test-000000000000000000000000001",
    "oauth-bridge.delegation-signing-secret=delegation-447-test-00000000000000000000000001"
})
class OAuthServerSignInIntegrationTest extends AbstractIT {
    private static final Duration TIMEOUT=Duration.ofSeconds(30);
    private static final String SERVICE_CREDENTIAL="identity-447-test-000000000000000000000000001";
    @MockitoBean OutboxEventScheduler scheduler;
    @Autowired OAuthFlowService flow;
    @Autowired OAuthFlowStore store;
    @Autowired OAuthProperties properties;
    @Autowired ru.taska.config.props.SignupProperties signup;
    @Autowired UserRepository users;
    @Autowired DatabaseClient db;
    @Autowired GrpcServerLifecycle grpc;
    private FakeOAuthProvider fake;
    private ManagedChannel channel;
    @BeforeEach void prepare() throws Exception {
        fake=new FakeOAuthProvider();fake.configure(properties);
        channel=ManagedChannelBuilder.forAddress("127.0.0.1",grpc.getPort()).usePlaintext().build();
    }
    @AfterEach void close() {channel.shutdownNow();try{fake.assertHealthy();}finally{fake.close();}}

    @Test void passwordlessAccountsSignInForEveryProviderWithoutJwtOrRefresh() {
        for(String provider:List.of("google","github","apple")) {
            User user=user(UserStatus.ACTIVE);
            String nonce=OAuthFlowStore.random();
            String code=callback(provider,user.getEmail(),nonce);
            assertThat(count("SELECT count(*) FROM taska.refresh_tokens WHERE user_id=:id",user.getId())).isZero();
            var result=stub().claimSignIn(request(code,nonce)).getUserContext();
            assertThat(result.getUserId()).isEqualTo(user.getId().toString());
            assertThat(result.getEmail()).isEqualTo(user.getEmail());
            assertThat(count("SELECT count(*) FROM taska.credentials WHERE user_id=:id AND credential_type='PASSWORD'",user.getId())).isZero();
            assertThat(count("SELECT count(*) FROM taska.credentials WHERE user_id=:id",user.getId())).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM taska.refresh_tokens WHERE user_id=:id",user.getId())).isZero();
            assertThat(users.findById(user.getId()).block(TIMEOUT).getDisplayName()).isEqualTo("Existing");
            assertThatThrownBy(()->stub().claimSignIn(request(code,nonce))).satisfies(error->assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        }
    }
    @Test void wrongBrowserAndPublicExchangeCannotConsumeAuthorizationServerGrant() {
        User user=user(UserStatus.ACTIVE);String nonce=OAuthFlowStore.random(),code=callback("google",user.getEmail(),nonce);
        reason(()->flow.claimSignIn(code,OAuthFlowStore.random(),"foreign-browser").block(TIMEOUT),"STATE_INVALID");
        reason(()->flow.exchange(code,"public-exchange").block(TIMEOUT),"STATE_INVALID");
        assertThat(flow.claimSignIn(code,nonce,"owner").block(TIMEOUT).getUserId()).isEqualTo(user.getId().toString());
        assertThat(count("SELECT count(*) FROM taska.refresh_tokens WHERE user_id=:id",user.getId())).isZero();
    }
    @Test void publicUiGrantCannotBeClaimedForAuthorizationServer() {
        User user=user(UserStatus.ACTIVE);String nonce=OAuthFlowStore.random();
        String code=store.grant("EXCHANGE",new OAuthIdentity("google",UUID.randomUUID().toString(),user.getEmail(),"Provider"),"/projects").block(TIMEOUT);
        reason(()->flow.claimSignIn(code,nonce,"wrong-target").block(TIMEOUT),"STATE_INVALID");
        assertThat(flow.exchange(code,"ui-owner").block(TIMEOUT).session()).isNotNull();
    }
    @Test void signupRequiredDoesNotCreateAccountCredentialOrSession() {
        try {
            for (boolean enabled : List.of(true, false)) {
                signup.setEnabled(enabled);
                String email=UUID.randomUUID()+"@example.test",nonce=OAuthFlowStore.random(),code=callback("google",email,nonce);
                reason(()->flow.claimSignIn(code,nonce,"unknown-account").block(TIMEOUT),"SIGNUP_REQUIRED");
                assertThat(users.findByEmail(email).block(TIMEOUT)).isNull();
                reason(()->flow.claimSignIn(code,nonce,"replay").block(TIMEOUT),"STATE_INVALID");
            }
        } finally { signup.setEnabled(true); }
    }
    @Test void accountBlockIsRecheckedBeforeLinkingAndDoesNotIssueSession() {
        User user=user(UserStatus.ACTIVE);String nonce=OAuthFlowStore.random(),code=callback("google",user.getEmail(),nonce);
        db.sql("UPDATE taska.users SET status='BLOCKED' WHERE id=:id").bind("id",user.getId()).fetch().rowsUpdated().block(TIMEOUT);
        reason(()->flow.claimSignIn(code,nonce,"blocked").block(TIMEOUT),"ACCOUNT_BLOCKED");
        assertThat(count("SELECT count(*) FROM taska.credentials WHERE user_id=:id",user.getId())).isZero();
        assertThat(count("SELECT count(*) FROM taska.refresh_tokens WHERE user_id=:id",user.getId())).isZero();
    }
    @Test void privateRpcRequiresCorrectServiceIdentityWithoutConsumingCode() {
        User user=user(UserStatus.ACTIVE);String nonce=OAuthFlowStore.random(),code=callback("github",user.getEmail(),nonce);
        for(var denied:List.of(OAuthIdentityServiceGrpc.newBlockingStub(channel),
                OAuthIdentityServiceGrpc.newBlockingStub(channel).withInterceptors(OAuthServiceCredentials.interceptor("wrong-identity")))) {
            assertThatThrownBy(()->denied.claimSignIn(request(code,nonce))).satisfies(error->assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
            assertThatThrownBy(()->denied.listSignInProviders(Empty.getDefaultInstance())).satisfies(error->assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        }
        assertThat(stub().claimSignIn(request(code,nonce)).getUserContext().getUserId()).isEqualTo(user.getId().toString());
        assertThat(stub().listSignInProviders(Empty.getDefaultInstance()).getProvidersList()).containsExactly("GOOGLE","GITHUB","APPLE");
    }
    @Test void concurrentClaimsHaveExactlyOneWinner() {
        User user=user(UserStatus.ACTIVE);String nonce=OAuthFlowStore.random(),code=callback("google",user.getEmail(),nonce);
        var results=Flux.merge(flow.claimSignIn(code,nonce,"one").map(x->"ok").onErrorResume(e->{assertReason(e,"STATE_INVALID");return reactor.core.publisher.Mono.just("invalid");}),
                flow.claimSignIn(code,nonce,"two").map(x->"ok").onErrorResume(e->{assertReason(e,"STATE_INVALID");return reactor.core.publisher.Mono.just("invalid");})).collectList().block(TIMEOUT);
        assertThat(results).containsExactlyInAnyOrder("ok","invalid");
        assertThat(count("SELECT count(*) FROM taska.credentials WHERE user_id=:id",user.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM taska.refresh_tokens WHERE user_id=:id",user.getId())).isZero();
    }
    @Test void validationAndDatabaseConstraintsPreventUnboundOrArbitraryTargets() {
        for(String target:List.of("https://evil.example","api","UI")) reason(()->flow.start("google","/projects",target,OAuthFlowStore.random()).block(TIMEOUT),"STATE_INVALID");
        reason(()->flow.start("google","/projects","oauth",null).block(TIMEOUT),"STATE_INVALID");
        reason(()->flow.start("google","/projects","ui",OAuthFlowStore.random()).block(TIMEOUT),"STATE_INVALID");
        assertThatThrownBy(()->store.saveState(OAuthFlowStore.random(),OAuthFlowStore.random(),new OAuthFlowStore.State("google","n","v","/","oauth",null)).block(TIMEOUT)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void expiredGrantAndProviderCancellationCannotCreateSignInSession() {
        User user=user(UserStatus.ACTIVE);String nonce=OAuthFlowStore.random(),code=callback("google",user.getEmail(),nonce);
        db.sql("UPDATE taska.oauth_grants SET expires_at=now()-interval '1 second' WHERE code_hash=:hash").bind("hash",OAuthFlowStore.hash(code)).fetch().rowsUpdated().block(TIMEOUT);
        reason(()->flow.claimSignIn(code,nonce,"expired").block(TIMEOUT),"STATE_INVALID");
        var start=flow.start("google","/projects","oauth",OAuthFlowStore.binding(nonce)).block(TIMEOUT);
        String error=flow.callback("google",start.state(),start.browserToken(),"","access_denied","","trace-447").block(TIMEOUT);
        assertThat(error).isEqualTo("https://oauth.example.test/login/provider/callback?error=PROVIDER_CANCELLED&requestId=trace-447");
        assertThat(count("SELECT count(*) FROM taska.refresh_tokens WHERE user_id=:id",user.getId())).isZero();
    }
    private User user(UserStatus status){return users.save(User.builder().email(UUID.randomUUID()+"@example.test").displayName("Existing").status(status).createdVia(UserCreatedVia.OAUTH).build()).block(TIMEOUT);}
    private String callback(String provider,String email,String nonce){
        var start=flow.start(provider,"/projects","oauth",OAuthFlowStore.binding(nonce)).block(TIMEOUT);
        String subject=provider.equals("github")?Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE)):UUID.randomUUID().toString();
        String providerCode=fake.issue(provider,subject,email,FakeOAuthProvider.query(URI.create(start.authorizationUrl()).getRawQuery()));
        String redirect=flow.callback(provider,start.state(),start.browserToken(),providerCode,"","{}").block(TIMEOUT);
        assertThat(redirect).startsWith("https://oauth.example.test/login/provider/callback?code=");
        return FakeOAuthProvider.query(URI.create(redirect).getRawQuery()).get("code");
    }
    private ClaimOAuthSignInRequest request(String code,String nonce){return ClaimOAuthSignInRequest.newBuilder().setHeader(Header.newBuilder().setNodeId("oauth-test").setRequestId(UUID.randomUUID().toString())).setCode(code).setNonce(nonce).build();}
    private OAuthIdentityServiceGrpc.OAuthIdentityServiceBlockingStub stub(){return OAuthIdentityServiceGrpc.newBlockingStub(channel).withInterceptors(OAuthServiceCredentials.interceptor(SERVICE_CREDENTIAL)).withDeadlineAfter(5,java.util.concurrent.TimeUnit.SECONDS);}
    private long count(String sql,UUID id){return db.sql(sql).bind("id",id).map(row->row.get(0,Long.class)).one().block(TIMEOUT);}
    private void reason(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String reason){assertThatThrownBy(action).satisfies(e->assertReason(e,reason));}
    private void assertReason(Throwable e,String reason){assertThat(e).isInstanceOfSatisfying(DomainException.class,d->assertThat(d.getDetails()).containsEntry("reason",reason));}
}
