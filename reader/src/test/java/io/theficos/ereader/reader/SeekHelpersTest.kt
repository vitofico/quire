package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SeekHelpersTest {

    private fun locatorAt(href: String, totalProgression: Double): Locator =
        Locator(
            href = Url(href)!!,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(
                progression = totalProgression,
                totalProgression = totalProgression,
            ),
        )

    private val positions: List<Locator> = (0..99).map {
        locatorAt("/ch${it / 10}#p$it", it / 99.0)
    }

    @Test fun `returns null when positions list is empty`() {
        assertThat(locatorAtPercent(emptyList(), 0.5)).isNull()
    }

    @Test fun `returns first position at percent 0`() {
        val result = locatorAtPercent(positions, 0.0)
        assertThat(result).isEqualTo(positions.first())
    }

    @Test fun `returns last position at percent 1`() {
        val result = locatorAtPercent(positions, 1.0)
        assertThat(result).isEqualTo(positions.last())
    }

    @Test fun `clamps negative percent to first position`() {
        assertThat(locatorAtPercent(positions, -0.5)).isEqualTo(positions.first())
    }

    @Test fun `clamps over-one percent to last position`() {
        assertThat(locatorAtPercent(positions, 1.5)).isEqualTo(positions.last())
    }

    @Test fun `rounds to nearest index in the middle`() {
        // index = round(0.5 * 99) = 50
        assertThat(locatorAtPercent(positions, 0.5)).isEqualTo(positions[50])
    }

    @Test fun `single-element list always returns that element`() {
        val single = listOf(locatorAt("/only", 0.0))
        assertThat(locatorAtPercent(single, 0.0)).isEqualTo(single[0])
        assertThat(locatorAtPercent(single, 0.5)).isEqualTo(single[0])
        assertThat(locatorAtPercent(single, 1.0)).isEqualTo(single[0])
    }

    @Test fun `two-element list rounds at half`() {
        val two = listOf(
            locatorAt("/a", 0.0),
            locatorAt("/b", 1.0),
        )
        // size = 2, so multiplier is (2 - 1) = 1.
        // percent < 0.5 → idx = 0; percent >= 0.5 → idx = 1.
        assertThat(locatorAtPercent(two, 0.0)).isEqualTo(two[0])
        assertThat(locatorAtPercent(two, 0.49)).isEqualTo(two[0])
        assertThat(locatorAtPercent(two, 0.5)).isEqualTo(two[1])
        assertThat(locatorAtPercent(two, 1.0)).isEqualTo(two[1])
    }
}
