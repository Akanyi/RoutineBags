package dev.lans.routinebags.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MountedPositionTest {
    @Test
    void savedPositionsRemainInsideResizedWindows() {
        for (int size : new int[] {120, 320, 640, 1920}) {
            for (int saved : new int[] {0, 2500, 5000, 10000}) {
                int x = MountedPosition.resolve(saved, -50, size, 20);
                assertTrue(x >= 4);
                assertTrue(x + 20 <= size - 4);
            }
        }
    }

    @Test
    void dragCoordinatesRoundTripWithinOnePixel() {
        for (int x = 4; x <= 502; x++) {
            int saved = MountedPosition.encode(x, 640, 130);
            assertTrue(Math.abs(x - MountedPosition.resolve(saved, 4, 640, 130)) <= 1);
        }
    }

    @Test
    void automaticAndOffscreenPlacementsAreClamped() {
        assertEquals(4, MountedPosition.resolve(-1, -100, 640, 130));
        assertEquals(506, MountedPosition.resolve(-1, 900, 640, 130));
        assertEquals(0, MountedPosition.encode(-100, 640, 130));
        assertEquals(10000, MountedPosition.encode(900, 640, 130));
        assertEquals(4, MountedPosition.resolve(10000, 0, 100, 130));
    }
}
