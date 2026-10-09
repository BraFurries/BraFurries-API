package com.Brafurries.API.meta.dto;

public class MetaDtos {
    public record LocaleDto(
            Integer id,
            String localeAbbrev,
            String localeName
    ) {
    }
}
