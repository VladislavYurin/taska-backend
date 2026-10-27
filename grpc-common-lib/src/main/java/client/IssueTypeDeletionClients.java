package client;

import java.util.UUID;
import ru.taska.api.common.v1.Header;
import ru.taska.api.registry.v1.IssueTypeDeletionKey;

/** Internal journal RPCs carry identity only; commands and attribution come from durable storage. */
public final class IssueTypeDeletionClients {
  private IssueTypeDeletionClients() {}
  public static IssueTypeDeletionKey key(UUID project, UUID operation) {
    return IssueTypeDeletionKey.newBuilder().setProjectId(project.toString())
        .setOperationId(operation.toString()).setHeader(Header.newBuilder()
            .setRequestId(UUID.randomUUID().toString()).setNodeId("issue-type-deletion")).build();
  }
}
