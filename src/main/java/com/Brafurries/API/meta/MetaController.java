package com.Brafurries.API.meta;

import com.Brafurries.API.entity.misc.Locale;
import com.Brafurries.API.meta.dto.MetaDtos;
import com.Brafurries.API.repository.misc.LocaleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/meta")
@Tag(name = "Meta", description = "Endpoints de metadados para o front-end")
public class MetaController {
    private final LocaleRepository localeRepository;

    public MetaController(LocaleRepository localeRepository) {
        this.localeRepository = localeRepository;
    }

    @Operation(summary = "Lista locales disponiveis")
    @GetMapping("/locales")
    public List<MetaDtos.LocaleDto> getLocales() {
        return localeRepository.findAll(Sort.by(Sort.Direction.ASC, "localeName"))
                .stream()
                .map(this::toLocaleDto)
                .toList();
    }

    private MetaDtos.LocaleDto toLocaleDto(Locale locale) {
        return new MetaDtos.LocaleDto(
                locale.getId(),
                locale.getLocaleAbbrev(),
                locale.getLocaleName()
        );
    }
}
