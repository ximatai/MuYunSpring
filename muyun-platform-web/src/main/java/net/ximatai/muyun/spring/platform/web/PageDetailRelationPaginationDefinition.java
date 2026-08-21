package net.ximatai.muyun.spring.platform.web;

import java.util.Arrays;
import java.util.List;

/** Stable paging policy for a directly managed detail relation. */
public record PageDetailRelationPaginationDefinition(boolean pageable,
                                                     Integer pageSize,
                                                     List<Integer> pageSizeOptions) {
    public static final PageDetailRelationPaginationDefinition DEFAULT =
            paged(20, 10, 20, 50);

    public static PageDetailRelationPaginationDefinition paged(int pageSize, int... pageSizeOptions) {
        return new PageDetailRelationPaginationDefinition(true, pageSize,
                Arrays.stream(pageSizeOptions).boxed().toList());
    }

    /** Loads the complete relation result and exposes no pagination controls. */
    public static PageDetailRelationPaginationDefinition unpaged() {
        return new PageDetailRelationPaginationDefinition(false, null, List.of());
    }

    public PageDetailRelationPaginationDefinition {
        pageSizeOptions = pageSizeOptions == null ? List.of() : List.copyOf(pageSizeOptions);
        if (pageSizeOptions.stream().anyMatch(value -> value <= 0 || value > 500)) {
            throw new IllegalArgumentException("detail relation page size options must be between 1 and 500");
        }
        pageSizeOptions = pageSizeOptions.stream().distinct().sorted().toList();
        if (pageable && pageSizeOptions.isEmpty()) {
            throw new IllegalArgumentException("pageable detail relation must declare page size options");
        }
        if (!pageable && !pageSizeOptions.isEmpty()) {
            throw new IllegalArgumentException("unpaged detail relation must not declare page size options");
        }
        if (!pageable && pageSize != null) {
            throw new IllegalArgumentException("unpaged detail relation must not declare a page size");
        }
        if (pageable && (pageSize == null || pageSize <= 0 || pageSize > 500
                || !pageSizeOptions.contains(pageSize))) {
            throw new IllegalArgumentException("detail relation default page size must be one of its options");
        }
    }
}
