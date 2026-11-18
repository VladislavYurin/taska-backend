package ru.taska.issuebootstrap;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.capability.TenantDecisionFacts;
import ru.taska.issuestorage.PrimaryIssueAccess;
import ru.taska.issuestorage.TenantIssueAccess;
import ru.taska.issuestorage.TenantIssueApi;
import ru.taska.issuestorage.TenantIssueGrpc;
import ru.taska.issuestorage.TenantIssueStore;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.RpcTrustBackend;

@Configuration(proxyBeanMethods = false)
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
@Import({TenantIssueGrpc.class,ru.taska.issuestorage.TenantAttachmentGrpc.class,ru.taska.issuestorage.AttachmentAdmissionGrpc.class})
public class IssueApiConfiguration {
  @Bean
  TenantDecisionFacts issueDecisionFacts(
      ConnectionFactory factory, ManagedDomainReads reads, RpcTrustBackend identity) {
    return new TenantDecisionFacts(
        new TenantTransactionExecutor(factory), reads, identity, Map.of(), Clock.systemUTC());
  }

  @Bean
  PrimaryIssueAccess primaryIssueAccess(
      TenantDecisionFacts facts, ManagedDomainReads reads, RpcContextSecurity security) {
    return new PrimaryIssueAccess(facts, reads, security);
  }

  @Bean
  ru.taska.issuestorage.AttachmentUploadStore attachmentUploadStore(ConnectionFactory factory) {
    return new ru.taska.issuestorage.AttachmentUploadStore(new TenantTransactionExecutor(factory),Clock.systemUTC());
  }
  @Bean
  ru.taska.issuestorage.AttachmentUploadPolicy attachmentUploadPolicy(TenantDecisionFacts facts,ManagedDomainReads reads,RpcContextSecurity security,TenantIssueStore issues) {
    return new ru.taska.issuestorage.PrimaryAttachmentPolicy(facts,reads,security,issues);
  }
  @Bean
  ru.taska.storage.versioned.UploadObjects attachmentUploadObjects(software.amazon.awssdk.services.s3.S3AsyncClient s3,
      software.amazon.awssdk.services.s3.presigner.S3Presigner signer,ru.taska.storage.versioned.VersionedObjects objects,
      ru.taska.storage.config.StorageProperties properties) {
    return new ru.taska.storage.versioned.S3UploadObjects(s3,signer,objects,properties.getBucket(),Clock.systemUTC());
  }
  @Bean
  ru.taska.issuestorage.AttachmentUploads attachmentUploads(ru.taska.issuestorage.AttachmentUploadStore store,
      ru.taska.issuestorage.StorageQuotaOwner owner,ru.taska.quota.StorageQuotaProtocol.Authority authority,
      ru.taska.storage.versioned.UploadObjects objects,ru.taska.issuestorage.AttachmentUploadPolicy policy) {
    return new ru.taska.issuestorage.AttachmentUploads(store,owner,authority,objects,policy,Clock.systemUTC());
  }

  @Bean
  TenantIssueApi tenantIssueApi(TenantIssueStore store, TenantIssueAccess access) {
    return new TenantIssueApi(store, access);
  }
}
