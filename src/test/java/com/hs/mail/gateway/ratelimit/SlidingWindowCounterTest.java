package com.hs.mail.gateway.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowCounterTest {

    private SlidingWindowCounter counter;

    @AfterEach
    void tearDown() {
        if (counter != null) {
            counter.shutdown();
        }
    }

    @Test
    void 같은_IP는_호출할때마다_누적된다() {
        counter = new SlidingWindowCounter(60);
        assertEquals(1, counter.incrementAndGet("1.2.3.4"));
        assertEquals(2, counter.incrementAndGet("1.2.3.4"));
        assertEquals(3, counter.incrementAndGet("1.2.3.4"));
    }

    @Test
    void 다른_IP는_서로_독립적으로_카운트된다() {
        counter = new SlidingWindowCounter(60);
        counter.incrementAndGet("1.1.1.1");
        counter.incrementAndGet("1.1.1.1");
        assertEquals(1, counter.incrementAndGet("2.2.2.2"));
    }

    @Test
    void 윈도우_경과_후에는_카운트가_리셋된다() throws InterruptedException {
        counter = new SlidingWindowCounter(1);
        counter.incrementAndGet("3.3.3.3");
        counter.incrementAndGet("3.3.3.3");
        Thread.sleep(1300);
        int afterReset = counter.incrementAndGet("3.3.3.3");
        assertTrue(afterReset <= 1, "윈도우 리셋 후에는 카운트가 다시 1부터 시작해야 함");
    }
}
