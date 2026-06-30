package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayUserContext;


@Component
public class ContextMapper {

    public GatewayUserContext mapToGatewayUserContext(UserContext proto) {
        return new GatewayUserContext(
                proto.getUserId(),
                proto.getLogin(),
                proto.getEmail(),
                proto.getDisplayName(),
                proto.getStatus()
        );
    }
}
