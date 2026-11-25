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
@Import({
  TenantIssueGrpc.class,
  ru.taska.issuestorage.TenantAttachmentGrpc.class,
  ru.taska.issuestorage.AttachmentAdmissionGrpc.class
})
public class IssueApiConfiguration {
  @Bean
  TenantDecisionFacts issueDecisionFacts(
      ConnectionFactory factory, ManagedDomainReads reads, RpcTrustBackend identity) {
    return new TenantDecisionFacts(
        new TenantTransactionExecutor(factory),
        reads,
        identity,
        Map.ofEntries(
            Map.entry(
                "storageQuotaInspect",
                storageBinding(
                    ru.taska.api.b2b.v1.StorageQuotaOwnerServiceGrpc.getInspectMethod()
                        .getFullMethodName())),
            Map.entry(
                "storageQuotaActivate",
                storageBinding(
                    ru.taska.api.b2b.v1.StorageQuotaOwnerServiceGrpc.getActivateMethod()
                        .getFullMethodName())),
            Map.entry(
                "storageQuotaCancel",
                storageBinding(
                    ru.taska.api.b2b.v1.StorageQuotaOwnerServiceGrpc.getCancelMethod()
                        .getFullMethodName())),
            Map.entry(
                "storageQuotaManifests",
                storageBinding(
                    ru.taska.api.b2b.v1.StorageQuotaOwnerServiceGrpc.getManifestsMethod()
                        .getFullMethodName()))),
        Clock.systemUTC());
  }

  private static TenantDecisionFacts.Binding storageBinding(String rpc) {
    return new TenantDecisionFacts.Binding(rpc, new RpcContextSecurity.Policy(true, false));
  }

  @Bean
  PrimaryIssueAccess primaryIssueAccess(
      TenantDecisionFacts facts, ManagedDomainReads reads, RpcContextSecurity security) {
    return new PrimaryIssueAccess(facts, reads, security);
  }

  @Bean
  ru.taska.issuestorage.AttachmentUploadStore attachmentUploadStore(
      ConnectionFactory factory, ru.taska.storage.config.StorageProperties properties) {
    return new ru.taska.issuestorage.AttachmentUploadStore(
        new TenantTransactionExecutor(factory),
        Clock.systemUTC(),
        java.util.Set.copyOf(properties.getAllowedContentTypes()));
  }

  @Bean
  ru.taska.issuestorage.AttachmentUploadPolicy attachmentUploadPolicy(
      TenantDecisionFacts facts,
      ManagedDomainReads reads,
      RpcContextSecurity security,
      TenantIssueStore issues,
      ru.taska.issuestorage.AttachmentLifecyclePolicy lifecycle) {
    return new ru.taska.issuestorage.PrimaryAttachmentPolicy(
        facts, reads, security, issues, lifecycle);
  }

  @Bean
  ru.taska.issuestorage.AttachmentLifecycleStore attachmentLifecycleStore(
      ConnectionFactory factory) {
    return new ru.taska.issuestorage.AttachmentLifecycleStore(
        new TenantTransactionExecutor(factory));
  }

  @Bean
  ru.taska.issuestorage.AttachmentLifecyclePolicy attachmentLifecyclePolicy(
      ru.taska.issuestorage.AttachmentLifecycleStore store,
      TenantIssueStore issues,
      TenantIssueAccess access) {
    return new ru.taska.issuestorage.AttachmentLifecyclePolicy(store, issues, access);
  }

  @Bean
  ru.taska.issuestorage.AttachmentDeletions attachmentDeletions(
      ru.taska.issuestorage.AttachmentLifecycleStore store,
      ru.taska.issuestorage.StorageQuotaOwner owner,
      ru.taska.quota.StorageQuotaProtocol.Authority root) {
    return new ru.taska.issuestorage.AttachmentDeletions(store, owner, root);
  }

  @Bean
  ru.taska.storage.versioned.AttachmentContentObjects attachmentContentObjects(
      software.amazon.awssdk.services.s3.S3AsyncClient s3,
      ru.taska.storage.config.StorageProperties properties) {
    return new ru.taska.storage.versioned.S3AttachmentContentObjects(s3, properties.getBucket());
  }

  @Bean
  ru.taska.issuestorage.AttachmentLifecycle attachmentLifecycle(
      ru.taska.issuestorage.AttachmentLifecycleStore store,
      ru.taska.issuestorage.AttachmentLifecyclePolicy policy,
      ru.taska.storage.versioned.AttachmentContentObjects objects) {
    return new ru.taska.issuestorage.AttachmentLifecycle(store, policy, objects);
  }

  @Bean
  ru.taska.storage.versioned.UploadObjects attachmentUploadObjects(
      software.amazon.awssdk.services.s3.S3AsyncClient s3,
      software.amazon.awssdk.services.s3.presigner.S3Presigner signer,
      ru.taska.storage.versioned.VersionedObjects objects,
      ru.taska.storage.config.StorageProperties properties) {
    return new ru.taska.storage.versioned.S3UploadObjects(
        s3, signer, objects, properties.getBucket(), Clock.systemUTC());
  }

  @Bean
  ru.taska.issuestorage.AttachmentUploads attachmentUploads(
      ru.taska.issuestorage.AttachmentUploadStore store,
      ru.taska.issuestorage.StorageQuotaOwner owner,
      ru.taska.quota.StorageQuotaProtocol.Authority authority,
      ru.taska.storage.versioned.UploadObjects objects,
      ru.taska.issuestorage.AttachmentUploadPolicy policy) {
    return new ru.taska.issuestorage.AttachmentUploads(
        store, owner, authority, objects, policy, Clock.systemUTC());
  }

  @Bean
  TenantIssueApi tenantIssueApi(TenantIssueStore store, TenantIssueAccess access) {
    return new TenantIssueApi(store, access);
  }
}
