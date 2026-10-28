package cn.kylebing.meifeng.wubi.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 输入 date / time / week 时插入基于系统时间的候选，与 iOS 一致 */
object DateTimeCandidates {
    fun candidates(preedit: String): List<String>? {
        val key = preedit.trim().lowercase()
        if (key.isEmpty()) return null
        val now = Date()
        return when (key) {
            "date" -> unique(dateFormats(now) + dateTimeFormats(now))
            "time" -> timeFormats(now)
            "week" -> weekFormats(now)
            else -> null
        }
    }

    private fun dateFormats(date: Date): List<String> {
        val cal = Calendar.getInstance()
        cal.time = date
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val mm = "%02d".format(m)
        val dd = "%02d".format(d)
        val weekday = weekdayCN(cal.get(Calendar.DAY_OF_WEEK))
        return unique(
            listOf(
                "$y-$mm-$dd", "$y/$mm/$dd", "$y.$mm.$dd", "$y$mm$dd",
                "${y}年${m}月${d}日", "${m}月${d}日", "$mm-$dd", "$mm/$dd",
                weekday, "${y}年${m}月${d}日 $weekday"
            )
        )
    }

    private fun timeFormats(date: Date): List<String> {
        val cal = Calendar.getInstance()
        cal.time = date
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val mi = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        val hh = "%02d".format(h)
        val mm = "%02d".format(mi)
        val ss = "%02d".format(s)
        val ts = date.time / 1000
        val tsMs = date.time
        return unique(
            listOf(
                "$hh:$mm:$ss", "$hh:$mm", "$ts", "${h}时${mi}分",
                "$hh$mm$ss", "$hh$mm", "${h}时${mi}分${s}秒", "$tsMs"
            )
        )
    }

    private fun weekFormats(date: Date): List<String> {
        val cal = Calendar.getInstance()
        cal.time = date
        val full = weekdayCN(cal.get(Calendar.DAY_OF_WEEK))
        val short = full.replace("星期", "周")
        val libai = full.replace("星期", "礼拜")
        val iso = Calendar.getInstance()
        iso.minimalDaysInFirstWeek = 4
        iso.firstDayOfWeek = Calendar.MONDAY
        iso.time = date
        val week = iso.get(Calendar.WEEK_OF_YEAR)
        val weekYear = iso.get(Calendar.YEAR)
        val enFull = SimpleDateFormat("EEEE", Locale.US).format(date)
        val enShort = SimpleDateFormat("EEE", Locale.US).format(date)
        return unique(
            listOf(
                full, short, "第${week}周", "${weekYear}年第${week}周",
                libai, enFull, enShort, "$weekYear-W${"%02d".format(week)}"
            )
        )
    }

    private fun dateTimeFormats(date: Date): List<String> {
        val cal = Calendar.getInstance()
        cal.time = date
        val y = cal.get(Calendar.YEAR)
        val mo = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val mi = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        val mm = "%02d".format(mo)
        val dd = "%02d".format(d)
        val hh = "%02d".format(h)
        val mi2 = "%02d".format(mi)
        val ss = "%02d".format(s)
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        iso.timeZone = TimeZone.getDefault()
        return unique(
            listOf(
                "$y-$mm-$dd $hh:$mi2:$ss",
                "$y/$mm/$dd $hh:$mi2:$ss",
                "$y$mm$dd$hh$mi2$ss",
                "${y}年${mo}月${d}日 ${h}时${mi}分${s}秒",
                "$y-$mm-$dd $hh:$mi2",
                iso.format(date)
            )
        )
    }

    private fun weekdayCN(dayOfWeek: Int): String {
        val names = arrayOf("", "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        return names.getOrElse(dayOfWeek) { "" }
    }

    private fun unique(items: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        items.forEach { if (it.isNotEmpty()) seen.add(it) }
        return seen.toList()
    }
}
