package dev.cline.termux.ui.schedules

import android.content.res.Resources
import dev.cline.termux.R
import dev.cline.termux.data.schedules.ScheduleWords

/** The schedule formatters' copy (CR §4.5, desktop routine-view.tsx wording) from strings_schedules.xml. */
fun scheduleWords(resources: Resources): ScheduleWords = ScheduleWords(
    everyDay = resources.getString(R.string.schedule_words_every_day),
    weekdays = resources.getString(R.string.schedule_words_weekdays),
    daily = resources.getString(R.string.schedule_editor_daily),
    once = resources.getString(R.string.schedule_editor_once),
    statusAt = resources.getString(R.string.schedule_words_status_at),
    unknownStatus = resources.getString(R.string.schedule_words_unknown_status),
    dayShort = resources.getStringArray(R.array.schedule_day_short).toList(),
    triggerPair = resources.getString(R.string.schedule_trigger_pair),
    triggerWithZone = resources.getString(R.string.schedule_trigger_with_zone),
    daysJoin = resources.getString(R.string.schedule_days_join),
)
