package com.incidentplatform.shared.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantContext")
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("set and get")
    class SetAndGet {

        @Test
        @DisplayName("should set and get tenantId")
        void shouldSetAndGetTenantId() {
            TenantContext.set("acme-corp");
            assertThat(TenantContext.get()).isEqualTo("acme-corp");
        }

        @Test
        @DisplayName("should throw when tenantId not set")
        void shouldThrowWhenNotSet() {
            assertThatThrownBy(TenantContext::get)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TenantContext is not set");
        }

        @Test
        @DisplayName("getOrNull should return null when not set")
        void getOrNullShouldReturnNullWhenNotSet() {
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("isSet should return true after set")
        void isSetShouldReturnTrueAfterSet() {
            TenantContext.set("acme-corp");
            assertThat(TenantContext.isSet()).isTrue();
        }

        @Test
        @DisplayName("isSet should return false when not set")
        void isSetShouldReturnFalseWhenNotSet() {
            assertThat(TenantContext.isSet()).isFalse();
        }

        @Test
        @DisplayName("should not set null tenantId")
        void shouldNotSetNullTenantId() {
            TenantContext.set(null);
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should not set blank tenantId")
        void shouldNotSetBlankTenantId() {
            TenantContext.set("   ");
            assertThat(TenantContext.getOrNull()).isNull();
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("should clear tenantId")
        void shouldClearTenantId() {
            TenantContext.set("acme-corp");
            TenantContext.clear();
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("clear should not throw when tenantId not set")
        void clearShouldNotThrowWhenNotSet() {
            TenantContext.clear();
            assertThat(TenantContext.getOrNull()).isNull();
        }
    }

    @Nested
    @DisplayName("thread isolation — kluczowy test po zmianie na ThreadLocal")
    class ThreadIsolation {

        @Test
        @DisplayName("should NOT propagate tenantId to child thread (ThreadLocal behaviour)")
        void shouldNotPropagateTenantIdToChildThread()
                throws InterruptedException {
            // given
            TenantContext.set("acme-corp");

            final AtomicReference<String> childTenantId = new AtomicReference<>();
            final CountDownLatch latch = new CountDownLatch(1);

            // when
            final Thread childThread = new Thread(() -> {
                childTenantId.set(TenantContext.getOrNull());
                latch.countDown();
            });
            childThread.start();
            latch.await();

            // then
            assertThat(childTenantId.get())
                    .as("Child thread should NOT inherit tenantId — " +
                            "ThreadLocal does not propagate unlike InheritableThreadLocal")
                    .isNull();

            assertThat(TenantContext.get()).isEqualTo("acme-corp");
        }

        @Test
        @DisplayName("should isolate tenantId between threads")
        void shouldIsolateTenantIdBetweenThreads()
                throws InterruptedException {
            // given
            final AtomicReference<String> thread1Value = new AtomicReference<>();
            final AtomicReference<String> thread2Value = new AtomicReference<>();
            final CountDownLatch latch = new CountDownLatch(2);

            // when
            new Thread(() -> {
                TenantContext.set("tenant-1");
                thread1Value.set(TenantContext.get());
                TenantContext.clear();
                latch.countDown();
            }).start();

            new Thread(() -> {
                TenantContext.set("tenant-2");
                thread2Value.set(TenantContext.get());
                TenantContext.clear();
                latch.countDown();
            }).start();

            latch.await();

            // then
            assertThat(thread1Value.get()).isEqualTo("tenant-1");
            assertThat(thread2Value.get()).isEqualTo("tenant-2");
        }

        @Test
        @DisplayName("TenantAwareTaskDecorator should propagate tenantId explicitly")
        void taskDecoratorShouldPropagateTenantId()
                throws InterruptedException {
            // given
            TenantContext.set("acme-corp");

            final AtomicReference<String> decoratedTenantId =
                    new AtomicReference<>();
            final CountDownLatch latch = new CountDownLatch(1);

            // when
            final Runnable decorated = new TenantAwareTaskDecorator()
                    .decorate(() -> {
                        decoratedTenantId.set(TenantContext.getOrNull());
                        latch.countDown();
                    });

            new Thread(decorated).start();
            latch.await();

            // then
            assertThat(decoratedTenantId.get())
                    .as("TenantAwareTaskDecorator should explicitly propagate tenantId")
                    .isEqualTo("acme-corp");
        }

        @Test
        @DisplayName("TenantAwareTaskDecorator should clear context after task")
        void taskDecoratorShouldClearContextAfterTask()
                throws InterruptedException {
            // given
            TenantContext.set("acme-corp");

            final AtomicReference<String> tenantAfterTask =
                    new AtomicReference<>();
            final CountDownLatch latch = new CountDownLatch(1);

            // when
            final Runnable decorated = new TenantAwareTaskDecorator()
                    .decorate(() -> { /* zadanie */ });

            new Thread(() -> {
                decorated.run();
                tenantAfterTask.set(TenantContext.getOrNull());
                latch.countDown();
            }).start();
            latch.await();

            // then
            assertThat(tenantAfterTask.get())
                    .as("Thread pool thread should have clean context after task")
                    .isNull();
        }
    }
}