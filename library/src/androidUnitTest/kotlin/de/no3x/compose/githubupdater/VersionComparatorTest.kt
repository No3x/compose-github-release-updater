package de.no3x.compose.githubupdater

import kotlin.test.Test
import kotlin.test.assertFalse

class VersionComparatorTest {
    private val comparator = VersionComparator.semver()

    @Test
    fun isNewerVersion_withTwoSegmentCandidate_handlesGracefully() {
        val result = comparator.isNewerVersion(current = "1.0.0", candidate = "1.0")
        assertFalse(result)
    }
}
