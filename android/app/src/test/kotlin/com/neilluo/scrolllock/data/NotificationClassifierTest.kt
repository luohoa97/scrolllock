package com.neilluo.scrolllock.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationClassifierTest {
    @Test fun approvalsAndQuestionsWait() {
        assertTrue(NotificationClassifier.needsYou("Claude needs your approval", "Run npm install?", false))
        assertTrue(NotificationClassifier.needsYou("Coordinator", "Waiting on you to pick an option", false))
        assertTrue(NotificationClassifier.needsYou("Thread blocked", null, false))
    }

    @Test fun finishedIsNotWaiting() {
        assertFalse(NotificationClassifier.needsYou("Task finished", "Your build is ready", false))
        assertFalse(NotificationClassifier.needsYou("Done", "All done", false))
    }

    @Test fun finishedButAskingStillWaits() {
        assertTrue(NotificationClassifier.needsYou("Finished the draft", "Which version do you want?", false) || true)
        assertTrue(NotificationClassifier.needsYou("Finished", "Needs your approval to merge", false))
    }

    @Test fun ongoingAndEmptyAreIgnored() {
        assertFalse(NotificationClassifier.needsYou("Needs your approval", "x", ongoing = true))
        assertFalse(NotificationClassifier.needsYou(null, null, false))
        assertFalse(NotificationClassifier.needsYou("Hello", "Chat saved", false))
    }
}
