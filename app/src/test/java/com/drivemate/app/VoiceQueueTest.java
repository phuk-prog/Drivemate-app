package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoiceQueueTest {
    @Test public void firstMessageAlwaysPlays() {
        VoiceQueue q = new VoiceQueue();
        assertEquals(VoiceQueue.Action.PLAY_NOW, q.offer(VoiceQueue.STATUS, "GPS ready", 0));
    }

    @Test public void emptyMessageIsDropped() {
        VoiceQueue q = new VoiceQueue();
        assertEquals(VoiceQueue.Action.DROP, q.offer(VoiceQueue.URGENT, "  ", 0));
        assertEquals(VoiceQueue.Action.DROP, q.offer(VoiceQueue.URGENT, null, 0));
    }

    @Test public void statusCannotCutOffATurn() {
        VoiceQueue q = new VoiceQueue();
        q.offer(VoiceQueue.TURN, "In 200 metres, turn left onto High Street", 0);
        assertEquals(VoiceQueue.Action.DROP, q.offer(VoiceQueue.STATUS, "GPS accuracy improved", 500));
        assertEquals(VoiceQueue.Action.DROP, q.offer(VoiceQueue.INFO, "Traffic ahead", 500));
    }

    @Test public void urgentTurnInterruptsEarlierTurn() {
        VoiceQueue q = new VoiceQueue();
        q.offer(VoiceQueue.TURN, "In 400 metres, turn left onto High Street", 0);
        assertEquals(VoiceQueue.Action.PLAY_NOW, q.offer(VoiceQueue.URGENT, "Turn left now", 1000));
    }

    @Test public void turnWaitsBehindTurnOfSamePriority() {
        VoiceQueue q = new VoiceQueue();
        q.offer(VoiceQueue.TURN, "In 400 metres, turn left onto High Street", 0);
        assertEquals(VoiceQueue.Action.QUEUE, q.offer(VoiceQueue.TURN, "Then at the roundabout take the 2nd exit", 500));
        // Only one message waits: a third is dropped.
        assertEquals(VoiceQueue.Action.DROP, q.offer(VoiceQueue.TURN, "Something else entirely", 600));
    }

    @Test public void higherPriorityInterruptsLower() {
        VoiceQueue q = new VoiceQueue();
        q.offer(VoiceQueue.STATUS, "Recalculating your route", 0);
        assertEquals(VoiceQueue.Action.PLAY_NOW, q.offer(VoiceQueue.TURN, "Turn right", 500));
    }

    @Test public void duplicateWithinFiveSecondsDropped() {
        VoiceQueue q = new VoiceQueue();
        q.offer(VoiceQueue.INFO, "Faster route available", 0);
        q.finished();
        assertEquals(VoiceQueue.Action.DROP, q.offer(VoiceQueue.INFO, "Faster route available", 3000));
        assertEquals(VoiceQueue.Action.PLAY_NOW, q.offer(VoiceQueue.INFO, "Faster route available", 6000));
    }

    @Test public void playsAgainOnceFinished() {
        VoiceQueue q = new VoiceQueue();
        q.offer(VoiceQueue.TURN, "Turn left", 0);
        q.finished();
        assertEquals(VoiceQueue.Action.PLAY_NOW, q.offer(VoiceQueue.STATUS, "GPS signal restored", 100));
    }

    @Test public void playsAgainAfterEstimatedEnd() {
        VoiceQueue q = new VoiceQueue();
        q.offer(VoiceQueue.TURN, "Turn left", 0);
        assertEquals(VoiceQueue.Action.PLAY_NOW, q.offer(VoiceQueue.STATUS, "Other", 20_000));
    }

    @Test public void resetAllowsRepeat() {
        VoiceQueue q = new VoiceQueue();
        q.offer(VoiceQueue.TURN, "Turn left", 0);
        q.reset();
        assertEquals(VoiceQueue.Action.PLAY_NOW, q.offer(VoiceQueue.URGENT, "Turn left", 500));
    }

    @Test public void estimateIsBounded() {
        assertEquals(400, VoiceQueue.estimateMs(""));
        assertEquals(12_000, VoiceQueue.estimateMs(new String(new char[500]).replace('\0', 'x')));
    }

    @Test public void staleRule() {
        assertFalse(VoiceQueue.stale(0, 8000));
        assertTrue(VoiceQueue.stale(0, 8001));
    }
}
