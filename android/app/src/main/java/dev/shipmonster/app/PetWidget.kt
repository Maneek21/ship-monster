package dev.shipmonster.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.widget.RemoteViews
import dev.shipmonster.app.core.Engine
import dev.shipmonster.app.core.Mood
import dev.shipmonster.app.core.Rules

/** The home screen widget. The system refreshes it every 30 minutes; the app refreshes it on every change. */
class PetWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = build(context)
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, views)
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PetWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, build(context))
        }

        private fun build(context: Context): RemoteViews {
            val g = GameStore.load(context)
            val now = GameStore.now(context)
            val tz = GameStore.tz(now)
            val mood = Engine.mood(g)
            val feral = mood == Mood.FERAL
            val lead = g.lead
            val views = RemoteViews(context.packageName, R.layout.widget_pet)
            views.setInt(R.id.widget_root, "setBackgroundResource", if (feral) R.drawable.widget_bg_monster else R.drawable.widget_bg)

            val ink = context.getColor(if (feral) R.color.on_monster else R.color.ink)
            val soft = context.getColor(if (feral) R.color.on_monster_soft else R.color.ink_soft)
            views.setTextColor(R.id.pet_name, ink)
            views.setTextColor(R.id.pet_say, ink)
            views.setTextColor(R.id.pet_meta, soft)

            if (lead == null) {
                val egg = g.starter
                views.setImageViewBitmap(R.id.pet_image,
                    Art.scaled(if (egg != null) Art.egg(context, egg, 0) else Art.egg(context, "mossback", 3), 6))
                views.setTextViewText(R.id.pet_name, if (egg != null) "An egg" else "Ship Monster")
                views.setTextViewText(R.id.pet_say, if (egg != null) "Hatches on your next push." else "Open the app to pick an egg.")
                views.setProgressBar(R.id.pet_belly, 100, 0, false)
                views.setTextViewText(R.id.pet_meta, "")
            } else {
                val hour = Rules.localHour(now, tz)
                val asleep = Engine.hibernating(g, now) || hour >= 23 || hour < 5
                views.setImageViewBitmap(R.id.pet_image,
                    Art.big(context, lead.species, lead.stage, Art.variantFor(g, lead, asleep), 240))
                views.setTextViewText(R.id.pet_name, lead.title)
                views.setTextViewText(R.id.pet_say, "\"" + GameStore.lines(context).idle(g, now, tz) + "\"")
                views.setProgressBar(R.id.pet_belly, 100, lead.belly.toInt(), false)
                if (android.os.Build.VERSION.SDK_INT >= 31) views.setColorStateList(R.id.pet_belly, "setProgressTintList",
                    ColorStateList.valueOf(context.getColor(stateColor(mood))))
                val due = Fmt.due(context, Engine.deadline(g, tz))
                val state = when {
                    Engine.isRest(g, now, tz) -> "Rest day"
                    mood == Mood.FERAL -> "FERAL. Falls $due"
                    mood == Mood.RESTLESS -> "Feral $due"
                    else -> "Restless $due"
                }
                views.setTextViewText(R.id.pet_meta, "$state  |  streak ${Engine.liveStreak(g, now, tz)}")
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            return views
        }

        fun stateColor(mood: Mood): Int = when (mood) {
            Mood.FERAL -> R.color.bad
            Mood.RESTLESS -> R.color.warn
            else -> R.color.ok
        }
    }
}
