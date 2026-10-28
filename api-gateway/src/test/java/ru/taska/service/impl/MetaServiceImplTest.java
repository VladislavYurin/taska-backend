package ru.taska.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import ru.taska.config.WebConfig;
import ru.taska.domain.dto.CreateIssueLinkRequestDto;
import ru.taska.domain.dto.IssueLinkTypeDto;
import ru.taska.domain.dto.IssueLinkViewTypeDto;
import ru.taska.domain.dto.IssueLinkViewTypeMetaPairDto;
import ru.taska.domain.dto.IssueLinkTypeMetaPairDto;
import ru.taska.domain.dto.IssuePriorityDto;
import ru.taska.domain.dto.IssuePriorityMetaPairDto;
import ru.taska.domain.dto.IssueTypeMetaPairDto;
import ru.taska.domain.dto.MetaResponseDto;
import ru.taska.domain.dto.NotificationTypeDto;
import ru.taska.domain.dto.NotificationTypeMetaPairDto;
import ru.taska.domain.dto.UserStatusDto;
import ru.taska.domain.dto.UserStatusMetaPairDto;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MetaServiceImpl Tests")
class MetaServiceImplTest {

    private final MetaServiceImpl service = new MetaServiceImpl();

    @Test
    @DisplayName("Каждый словарь перечисляет все значения enum-схемы контракта в её порядке")
    void meta_listsEveryContractEnumValue() {
        MetaResponseDto meta = service.getMeta();

        assertThat(meta.getActionChannels()).extracting(ru.taska.domain.dto.ActionChannelMetaPairDto::getCode)
                .containsExactly(java.util.Arrays.stream(ru.taska.domain.ActionChannel.values()).map(Enum::name).toArray(String[]::new));
        assertThat(meta.getIssueTypes()).extracting(IssueTypeMetaPairDto::getCode)
                .containsExactly(new String[]{"TASK", "BUG", "STORY"});
        assertThat(meta.getPriorities()).extracting(IssuePriorityMetaPairDto::getCode)
                .containsExactly(IssuePriorityDto.values());
        assertThat(meta.getLinkTypes()).extracting(IssueLinkTypeMetaPairDto::getCode)
                .containsExactly(IssueLinkTypeDto.values());
        assertThat(meta.getLinkViewTypes()).extracting(IssueLinkViewTypeMetaPairDto::getCode)
                .containsExactly(IssueLinkViewTypeDto.values());
        assertThat(meta.getNotificationTypes()).extracting(NotificationTypeMetaPairDto::getCode)
                .containsExactly(NotificationTypeDto.values());
        assertThat(meta.getUserStatuses()).extracting(UserStatusMetaPairDto::getCode)
                .containsExactly(UserStatusDto.values());
    }

    @Test
    @DisplayName("Названия значений заданы")
    void meta_everyValueHasName() {
        MetaResponseDto meta = service.getMeta();

        assertThat(meta.getActionChannels()).extracting(ru.taska.domain.dto.ActionChannelMetaPairDto::getName).containsExactly("UI", "API", "MCP");
        assertThat(meta.getIssueTypes()).extracting(IssueTypeMetaPairDto::getName).allMatch(this::isFilled);
        assertThat(meta.getPriorities()).extracting(IssuePriorityMetaPairDto::getName).allMatch(this::isFilled);
        assertThat(meta.getLinkTypes()).extracting(IssueLinkTypeMetaPairDto::getName).allMatch(this::isFilled);
        assertThat(meta.getLinkViewTypes()).extracting(IssueLinkViewTypeMetaPairDto::getName).allMatch(this::isFilled);
        assertThat(meta.getNotificationTypes()).extracting(NotificationTypeMetaPairDto::getName).allMatch(this::isFilled);
        assertThat(meta.getUserStatuses()).extracting(UserStatusMetaPairDto::getName).allMatch(this::isFilled);
    }

    @Test
    @DisplayName("Каждый issueType и priority из словаря принимается query-параметрами боевых маршрутов")
    void meta_codesAcceptedByQueryParameterConverters() {
        DefaultFormattingConversionService conversionService = new DefaultFormattingConversionService();
        new WebConfig().addFormatters(conversionService);
        MetaResponseDto meta = service.getMeta();

        assertThat(meta.getIssueTypes())
                .allSatisfy(item -> assertThat(conversionService.convert(item.getCode(), String.class))
                        .isEqualTo(item.getCode()));
        assertThat(meta.getPriorities())
                .allSatisfy(item -> assertThat(conversionService.convert(item.getCode().getValue(), IssuePriorityDto.class))
                        .isEqualTo(item.getCode()));
    }

    @Test
    @DisplayName("Каждый linkType из словаря принимается в теле запроса создания связи")
    void meta_linkTypesAcceptedInCreateIssueLinkBody() {
        ObjectMapper objectMapper = new ObjectMapper();
        MetaResponseDto meta = service.getMeta();

        assertThat(meta.getLinkTypes()).allSatisfy(item -> {
            String body = """
                    {"targetIssueId": "TAS-1", "linkType": "%s"}
                    """.formatted(item.getCode().getValue());

            assertThat(objectMapper.readValue(body, CreateIssueLinkRequestDto.class).getLinkType())
                    .isEqualTo(item.getCode());
        });
    }

    @Test
    @DisplayName("ETag — строгий, в кавычках, стабилен между экземплярами")
    void etag_isQuotedAndStable() {
        // Каждый байт хеша записывается двумя hex-символами
        int hexLength = MetaServiceImpl.ETAG_BYTES * 2;
        assertThat(service.getEtag()).matches("\"[0-9a-f]{" + hexLength + "}\"");
        assertThat(new MetaServiceImpl().getEtag()).isEqualTo(service.getEtag());
    }

    @Test
    void placeholdersAreDisplayOnlyAndNormalValuesRemainSelectable() {
        var meta = service.getMeta();
        assertThat(meta.getNotificationTypes()).allSatisfy(item ->
                assertThat(item.getSelectable()).isEqualTo(item.getCode() != NotificationTypeDto.UNKNOWN));
        assertThat(meta.getUserStatuses()).allSatisfy(item ->
                assertThat(item.getSelectable()).isEqualTo(item.getCode() != UserStatusDto.UNSPECIFIED));
        assertThat(meta.getLinkViewTypes()).allSatisfy(item -> assertThat(item.getSelectable()).isEqualTo(
                item.getCode() != IssueLinkViewTypeDto.ISSUE_LINK_VIEW_TYPE_UNSPECIFIED
                        && item.getCode() != IssueLinkViewTypeDto.UNRECOGNIZED));
        assertThat(meta.getIssueTypes()).allSatisfy(item -> assertThat(item.getSelectable()).isTrue());
        assertThat(meta.getPriorities()).allSatisfy(item -> assertThat(item.getSelectable()).isTrue());
        assertThat(meta.getLinkTypes()).allSatisfy(item -> assertThat(item.getSelectable()).isTrue());
    }

    @Test
    void olderMetaResponseWithoutActionChannelsRemainsReadable() {
        var mapper = new ObjectMapper();
        var legacy = mapper.readValue("""
                {"issueTypes":[],"priorities":[],"linkTypes":[],"notificationTypes":[],"userStatuses":[]}
                """, MetaResponseDto.class);
        assertThat(legacy.getIssueTypes()).isEmpty();
        assertThat(legacy.getUserStatuses()).isEmpty();
        assertThat(service.getMeta().getActionChannels()).hasSize(3);
    }

    private boolean isFilled(String name) {
        return name != null && !name.isBlank() && name.codePoints().noneMatch(
                c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC);
    }
}
