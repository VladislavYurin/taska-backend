package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("issue.list")
public record IssueListProperties(int defaultPageSize, int maxPageSize) {
}
