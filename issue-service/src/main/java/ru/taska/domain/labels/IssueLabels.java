package ru.taska.domain.labels;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "issue_labels",schema = "taska")
public class IssueLabels {

    @Id
    private UUID id;

    @Column("issue_id")
    private UUID issueId;

    @Column("label_id")
    private UUID labelId;

    @Column("created_by")
    private UUID createdBy;

    @Column("created_at")
    private Instant createdAt;

}
