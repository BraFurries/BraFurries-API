package com.Brafurries.API.common.dto;

import java.util.List;

public record PagedResponseDto<T>(
        List<T> items,
        Integer page,
        Integer size,
        Long totalItems,
        Integer totalPages,
        Boolean hasNext,
        Boolean hasPrevious
) {
}
