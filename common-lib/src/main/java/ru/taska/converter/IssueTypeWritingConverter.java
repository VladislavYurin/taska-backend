package ru.taska.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import ru.taska.domain.IssueType;

@WritingConverter
public class IssueTypeWritingConverter implements Converter<IssueType, String> {
    @Override
    public String convert(IssueType source) {
        return source.name();
    }
}
