package client;

import java.util.UUID;
import ru.taska.api.common.v1.Header;
import ru.taska.api.registry.v1.TypeOperationKey;
import ru.taska.api.registry.v1.TypeOperationProjectRequest;

public final class TypeOperationClients {
    private TypeOperationClients() { }
    private static Header header() { return Header.newBuilder().setRequestId(UUID.randomUUID().toString()).setNodeId("type-operation").build(); }
    public static TypeOperationKey key(UUID project, UUID operation) {
        return TypeOperationKey.newBuilder().setHeader(header()).setProjectId(project.toString()).setOperationId(operation.toString()).build();
    }
    public static TypeOperationProjectRequest project(UUID project) {
        return TypeOperationProjectRequest.newBuilder().setHeader(header()).setProjectId(project.toString()).build();
    }
}
