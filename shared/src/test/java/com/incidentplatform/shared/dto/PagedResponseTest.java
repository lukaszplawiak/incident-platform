package com.incidentplatform.shared.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PagedResponse")
class PagedResponseTest {

    // ── of(List, ...) — 7-arg factory ────────────────────────────────────

    @Nested
    @DisplayName("of(List, int, int, long, int, boolean, boolean)")
    class SevenArgFactory {

        @Test
        @DisplayName("maps all fields correctly")
        void mapsAllFields() {
            final List<String> content = List.of("a", "b");

            final PagedResponse<String> response = PagedResponse.of(
                    content, 2, 10, 25L, 3, false, false);

            assertThat(response.content()).isEqualTo(content);
            assertThat(response.page()).isEqualTo(2);
            assertThat(response.size()).isEqualTo(10);
            assertThat(response.totalElements()).isEqualTo(25L);
            assertThat(response.totalPages()).isEqualTo(3);
            assertThat(response.first()).isFalse();
            assertThat(response.last()).isFalse();
        }

        @Test
        @DisplayName("first page — first=true, last=false")
        void firstPage() {
            final PagedResponse<String> response = PagedResponse.of(
                    List.of("a"), 0, 10, 25L, 3, true, false);

            assertThat(response.first()).isTrue();
            assertThat(response.last()).isFalse();
        }

        @Test
        @DisplayName("last page — first=false, last=true")
        void lastPage() {
            final PagedResponse<String> response = PagedResponse.of(
                    List.of("a"), 2, 10, 25L, 3, false, true);

            assertThat(response.first()).isFalse();
            assertThat(response.last()).isTrue();
        }

        @Test
        @DisplayName("empty content — no violations")
        void emptyContent() {
            final PagedResponse<String> response = PagedResponse.of(
                    List.of(), 0, 20, 0L, 0, true, true);

            assertThat(response.content()).isEmpty();
            assertThat(response.totalElements()).isZero();
        }
    }

    // ── of(Page<T>) — convenience factory ────────────────────────────────

    @Nested
    @DisplayName("of(Page<T>)")
    class PageFactory {

        @Test
        @DisplayName("unwraps all Page metadata correctly")
        void unwrapsPageMetadata() {
            final List<String> items = List.of("x", "y", "z");
            final Page<String> page = new PageImpl<>(
                    items, PageRequest.of(1, 3), 9L);

            final PagedResponse<String> response = PagedResponse.of(page);

            assertThat(response.content()).isEqualTo(items);
            assertThat(response.page()).isEqualTo(1);
            assertThat(response.size()).isEqualTo(3);
            assertThat(response.totalElements()).isEqualTo(9L);
            assertThat(response.totalPages()).isEqualTo(3);
            assertThat(response.first()).isFalse();
            assertThat(response.last()).isFalse();
        }

        @Test
        @DisplayName("first page — first=true")
        void firstPage() {
            final Page<String> page = new PageImpl<>(
                    List.of("a"), PageRequest.of(0, 10), 25L);

            final PagedResponse<String> response = PagedResponse.of(page);

            assertThat(response.page()).isEqualTo(0);
            assertThat(response.first()).isTrue();
            assertThat(response.last()).isFalse();
        }

        @Test
        @DisplayName("last page — last=true")
        void lastPage() {
            final Page<String> page = new PageImpl<>(
                    List.of("a"), PageRequest.of(2, 10), 25L);

            final PagedResponse<String> response = PagedResponse.of(page);

            assertThat(response.page()).isEqualTo(2);
            assertThat(response.first()).isFalse();
            assertThat(response.last()).isTrue();
        }

        @Test
        @DisplayName("single page — first=true and last=true")
        void singlePage() {
            final Page<String> page = new PageImpl<>(
                    List.of("a", "b"), PageRequest.of(0, 10), 2L);

            final PagedResponse<String> response = PagedResponse.of(page);

            assertThat(response.first()).isTrue();
            assertThat(response.last()).isTrue();
            assertThat(response.totalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("empty page — totalElements=0, content empty")
        void emptyPage() {
            final Page<String> page = Page.empty(PageRequest.of(0, 20));

            final PagedResponse<String> response = PagedResponse.of(page);

            assertThat(response.content()).isEmpty();
            assertThat(response.totalElements()).isZero();
            assertThat(response.first()).isTrue();
            assertThat(response.last()).isTrue();
        }

        @Test
        @DisplayName("of(Page) delegates to of(List,...) — same result")
        void delegatesToSevenArgFactory() {
            final List<String> items = List.of("a", "b");
            final Page<String> page = new PageImpl<>(
                    items, PageRequest.of(0, 10), 2L);

            final PagedResponse<String> fromPage = PagedResponse.of(page);
            final PagedResponse<String> fromArgs = PagedResponse.of(
                    page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages(),
                    page.isFirst(), page.isLast());

            assertThat(fromPage).isEqualTo(fromArgs);
        }
    }
}