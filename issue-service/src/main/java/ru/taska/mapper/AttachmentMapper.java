package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.issue.attachment.v1.AttachmentResponse;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlResponse;
import ru.taska.api.issue.attachment.v1.GetAttachmentDownloadUrlResponse;
import ru.taska.api.issue.attachment.v1.ListAttachmentsResponse;
import ru.taska.domain.AttachmentDownloadUrlDto;
import ru.taska.domain.AttachmentDto;
import ru.taska.domain.IssueAttachment;
import ru.taska.storage.dto.PresignedUploadResult;

import java.util.List;

@Component
public class AttachmentMapper {

    public AttachmentResponse toAttachmentResponse(AttachmentDto attachmentDto) {
        IssueAttachment attachment = attachmentDto.issueAttachment();

        AttachmentResponse.Builder builder = AttachmentResponse.newBuilder()
                .setId(attachment.getId().toString())
                .setIssueId(attachment.getIssueId().toString())
                .setUploadedBy(attachment.getUploadedBy().toString())
                .setObjectKey(attachment.getObjectKey())
                .setFileName(attachment.getFileName())
                .setContentType(attachment.getContentType())
                .setSizeBytes(attachment.getSizeBytes())
                .setChecksum(attachment.getChecksum())
                .setCreatedAt(Timestamp.newBuilder()
                        .setSeconds(attachment.getCreatedAt().getEpochSecond())
                        .setNanos(attachment.getCreatedAt().getNano())
                        .build())
                .setUrl(attachmentDto.url());

        return builder.build();
    }

    public ListAttachmentsResponse toListAttachmentsResponse(List<AttachmentDto> attachments) {
        return ListAttachmentsResponse.newBuilder()
                .addAllAttachments(
                        attachments.stream()
                                .map(this::toAttachmentResponse)
                                .toList()
                )
                .build();
    }

    public CreateAttachmentUploadUrlResponse toUploadUrlResponse(PresignedUploadResult result) {
        return CreateAttachmentUploadUrlResponse.newBuilder()
                .setUploadUrl(result.url())
                .setObjectKey(result.objectKey())
                .build();
    }

    public GetAttachmentDownloadUrlResponse toDownloadUrlResponse(AttachmentDownloadUrlDto dto) {
        return GetAttachmentDownloadUrlResponse.newBuilder()
                .setUrl(dto.url())
                .setChecksum(dto.checksum())
                .build();
    }
}
