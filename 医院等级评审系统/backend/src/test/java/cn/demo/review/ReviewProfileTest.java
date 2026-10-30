package cn.demo.review;

import cn.demo.review.v2.platform.ReviewProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 运行 profile 边界：避免"以为在生产、实际还开着演示身份"。 */
class ReviewProfileTest {
    @Test void defaultsToDemoWhenUnset() {
        assertEquals("demo", ReviewProfile.resolve(null));
        assertEquals("demo", ReviewProfile.resolve("  "));
    }

    @Test void rejectsUnknownProfile() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ReviewProfile.resolve("staging"));
        assertTrue(error.getMessage().contains("demo 或 prod"));
    }

    @Test void prodSpringProfileRequiresProdReviewProfile() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ReviewProfile.resolve("demo", "prod"));
        assertTrue(error.getMessage().contains("review.profile=prod"));
        assertEquals("prod", ReviewProfile.resolve("prod", "prod"));
        assertEquals("demo", ReviewProfile.resolve("demo", "default"));
    }
}
