package action;

import java.util.UUID;
import reactor.util.context.Context;
import ru.taska.api.common.v1.Header;
import ru.taska.domain.ActionChannel;
import ru.taska.domain.ActionMetadata;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Request-local attribution, propagated with Reactor Context rather than thread locals. */
public final class GrpcActionContext {
    private GrpcActionContext() {}

    public static Context install(Context context, Header header) {
        return context.put(ActionMetadata.class, read(header));
    }

    public static ActionMetadata read(Header header) {
        try {
            if (!header.hasActionChannel()) {
                if (header.hasTokenId()) throw new IllegalArgumentException();
                return ActionMetadata.NONE;
            }
            var channel = ActionChannel.valueOf(header.getActionChannel());
            UUID token = header.hasTokenId() ? UUID.fromString(header.getTokenId()) : null;
            if ((channel == ActionChannel.UI && token != null) || (channel != ActionChannel.UI && token == null))
                throw new IllegalArgumentException();
            return new ActionMetadata(channel, token);
        } catch (IllegalArgumentException error) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid action attribution in gRPC header");
        }
    }

    public static Header.Builder enrich(Header.Builder builder, ActionMetadata metadata) {
        if (metadata.channel() != null) builder.setActionChannel(metadata.channel().name());
        if (metadata.tokenId() != null) builder.setTokenId(metadata.tokenId().toString());
        return builder;
    }
}
