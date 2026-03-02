package az.fitnest.payment.dto.common;

import lombok.Builder;

@Builder
public record PageCriteria(
    String sortBy,
    Integer page,
    Integer size,
    SortDirection direction
) {
    public Integer getPage() {
        return page != null && page > 0 ? page - 1 : 0;
    }

    public enum SortDirection {
        ASC, DESC
    }
}
