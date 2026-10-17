package dev.cline.termux.ui.schedules

import android.os.Parcel
import dev.cline.termux.data.schedules.ScheduleType
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleFormStateParcelTest {
    private fun roundTrip(state: ScheduleFormState): ScheduleFormState {
        val parcel = Parcel.obtain()
        try {
            state.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return ScheduleFormState.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `the form survives a parcel round trip`() {
        val state = ScheduleFormState(
            name = "Weekly review",
            type = ScheduleType.Weekly,
            date = LocalDate.of(2026, 10, 5),
            hour = 17,
            minute = 30,
            days = setOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY),
            prompt = "line one\nline two",
            provider = "openai",
            model = "gpt-b",
            workspaceRoot = "/w/app",
            systemPrompt = "be brief",
            timeoutSeconds = "3600",
            tags = "a,b",
            enabled = false,
            editingId = "s1",
        )
        assertEquals(state, roundTrip(state))
        val minimal = ScheduleFormState(date = LocalDate.of(2026, 1, 1), days = emptySet())
        assertEquals(minimal, roundTrip(minimal))
    }
}
