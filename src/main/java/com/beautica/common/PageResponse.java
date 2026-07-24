package com.beautica.common;

import java.util.List;

public record PageResponse<T>(
        boolean success,
        List<T> data,
        String message,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> of(
            List<T> data,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        return new PageResponse<>(true, data, null, page, size, totalElements, totalPages);
    }
}
