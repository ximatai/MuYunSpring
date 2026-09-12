package net.ximatai.muyun.spring.ability.logging;

/** Standard page coordinates used before the log store translates them into a descending cursor. */
public record BusinessLogPageRequest(int pageNum, int pageSize) {
    public static final int DEFAULT_PAGE_NUM = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAXIMUM_PAGE_SIZE = 500;
    public static final int MAXIMUM_OFFSET = 10_000;

    public BusinessLogPageRequest {
        pageNum = pageNum <= 0 ? DEFAULT_PAGE_NUM : pageNum;
        pageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;
        if (pageSize > MAXIMUM_PAGE_SIZE) {
            pageSize = MAXIMUM_PAGE_SIZE;
        }
        long offset = (long) (pageNum - 1) * pageSize;
        if (offset > MAXIMUM_OFFSET) {
            throw new IllegalArgumentException("business-log page offset must not exceed " + MAXIMUM_OFFSET);
        }
    }

    public static BusinessLogPageRequest defaults() {
        return new BusinessLogPageRequest(DEFAULT_PAGE_NUM, DEFAULT_PAGE_SIZE);
    }

    public long offset() {
        return (long) (pageNum - 1) * pageSize;
    }
}
