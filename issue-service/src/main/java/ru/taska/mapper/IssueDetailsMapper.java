package ru.taska.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValueMappingStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import ru.taska.api.issue.v1.GetIssueDetailsResponse;
import ru.taska.api.issue.v1.IssueDetailsResponse;
import ru.taska.api.issue.v1.IssueHistoryResponse;
import ru.taska.api.issue.v1.IssueLabelResponse;
import ru.taska.api.issue.v1.IssueLinkResponse;
import ru.taska.api.issue.v1.ListIssueLinksResponse;
import ru.taska.api.issue.v1.ListLabelsResponse;
import ru.taska.api.issue.v1.TargetIssueResponse;
import ru.taska.domain.AttachmentDto;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssueWatcher;
import ru.taska.domain.projection.IssueLinkDetails;
import ru.taska.domain.util.FetchResult;
import ru.taska.domain.aggregate.IssueDetailsAggregate;
import ru.taska.domain.projection.TargetIssue;
import ru.taska.domain.dto.UserSummary;
import ru.taska.domain.dto.labels.LabelResponses;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT,
        uses = {IssueMapperUtils.class, ProtoEnumMapper.class}
)
public abstract class IssueDetailsMapper {
    @Autowired
    protected IssueWatcherMapper issueWatcherMapper;
    @Autowired
    protected AttachmentMapper attachmentMapper;

    public GetIssueDetailsResponse toProto(IssueDetailsAggregate agg) {
        if (agg == null)
            return GetIssueDetailsResponse.getDefaultInstance();

        return GetIssueDetailsResponse.newBuilder()
                .setIssue(toIssueDetails(
                        agg.issueCore().issue(), agg.labels(), agg.watchers(),
                        agg.links(), agg.attachments(), agg.userProfiles(),
                        agg.issueCore().isWatching(), agg.issueCore().commentCount()))
                .addAllHistory(toHistoryList(agg.history()))
                .build();
    }

    @Mapping(target = "reporter", expression = "java(IssueMapperUtils.resolveUser(core.getReporterId(), profiles))")
    @Mapping(target = "status", source = "core.statusKey")
    @Mapping(target = "assignee", ignore = true)
    @Mapping(target = "labels", ignore = true)
    @Mapping(target = "watchers", ignore = true)
    @Mapping(target = "links", ignore = true)
    @Mapping(target = "attachments", ignore = true)
    abstract IssueDetailsResponse toIssueDetails(
            Issue core,
            FetchResult<LabelResponses.ProjectLabelInfo> labels,
            FetchResult<IssueWatcher> watchers,
            FetchResult<IssueLinkDetails> links,
            FetchResult<AttachmentDto> attachments,
            Map<UUID, UserSummary> profiles,
            boolean isWatching,
            long commentCount
    );

    /**
     * Дополняет {@link IssueDetailsResponse.Builder} данными, которые требуют
     * дополнительной обработки после основного маппинга MapStruct.
     *
     * <p>Метод вызывается MapStruct автоматически после выполнения
     * {@code toIssueDetails(...)}. Для каждого источника данные добавляются
     * только при успешной загрузке ({@link FetchResult#available()}).
     * Если источник недоступен, соответствующее поле не заполняется.</p>
     *
     * <p>Данные наблюдателей и вложений дополнительно обогащаются профилями
     * пользователей.</p>
     */
    @AfterMapping
    protected void mapCollections(
            @MappingTarget IssueDetailsResponse.Builder builder,
            Issue issue,
            FetchResult<LabelResponses.ProjectLabelInfo> labels,
            FetchResult<IssueWatcher> watchers,
            FetchResult<IssueLinkDetails> links,
            FetchResult<AttachmentDto> attachments,
            Map<UUID, UserSummary> profiles
    ) {
        if (labels.available()) {
            builder.setLabels(toLabelList(labels.items()));
        }
        if (watchers.available()) {
            builder.setWatchers(issueWatcherMapper.toListWatchersResponse(watchers.items(), profiles));
        }
        if (links.available()) {
            builder.setLinks(toLinkList(links.items(), issue.getId()));
        }
        if (attachments.available()) {
            builder.setAttachments(attachmentMapper.toListAttachmentsResponse(attachments.items(), profiles));
        }
        if (issue.getAssigneeId() != null) {
            builder.setAssignee(IssueMapperUtils.resolveUser(issue.getAssigneeId(), profiles));
        }
    }

    abstract IssueLabelResponse toLabelProto(LabelResponses.ProjectLabelInfo label);

    abstract List<IssueLabelResponse> toLabelProto(List<LabelResponses.ProjectLabelInfo> label);

    protected ListLabelsResponse toLabelList(List<LabelResponses.ProjectLabelInfo> labels){
        return ListLabelsResponse.newBuilder()
                .addAllLabels(toLabelProto(labels))
                .build();
    }

    @Mapping(target = ".", source = "link")
    @Mapping(
            target = "viewLinkType",
            expression = "java(IssueMapperUtils.resolveViewTypeToProto(link, issueId))"
    )
    abstract IssueLinkResponse toLinkProto(IssueLinkDetails link, @Context UUID issueId);

    abstract TargetIssueResponse toTargetProto(TargetIssue target);

    abstract List<IssueLinkResponse> toLinkResponses(List<IssueLinkDetails> links, @Context UUID issueId);

    protected ListIssueLinksResponse toLinkList(List<IssueLinkDetails> links, @Context UUID issueId) {
        return ListIssueLinksResponse.newBuilder()
                .addAllIssueLinks(toLinkResponses(links, issueId))
                .build();
    }

    abstract List<IssueHistoryResponse> toHistoryList(List<IssueHistory> history);

    abstract IssueHistoryResponse toHistoryEvent(IssueHistory event);
}

