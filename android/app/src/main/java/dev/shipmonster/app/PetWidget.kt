package dev.shipmonster.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.widget.RemoteViews

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
            val now = PetStore.now(context)
            val s = PetStore.load(context)
            val st = PetEngine.status(s, now)
            val monster = st == Status.MONSTER
            val v = RemoteViews(context.packageName, R.layout.widget_pet)

            v.setImageViewBitmap(R.id.pet_image, Sprites.forState(s, now, 16))
            v.setTextViewText(R.id.pet_name, PetText.title(s, now))
            v.setTextViewText(R.id.pet_state, PetText.pill(s, now))
            v.setTextViewText(R.id.pet_meta, PetText.meta(s, now))

            v.setInt(R.id.widget_root, "setBackgroundResource", if (monster) R.drawable.widget_bg_monster else R.drawable.widget_bg)
            v.setTextColor(R.id.pet_name, context.getColor(if (monster) R.color.on_monster else R.color.ink))
            v.setTextColor(R.id.pet_meta, context.getColor(if (monster) R.color.on_monster_soft else R.color.ink_soft))
            v.setTextColor(R.id.pet_state, context.getColor(stateColor(s, st)))

            val showBelly = st != Status.EGG && st != Status.DEAD
            v.setViewVisibility(R.id.pet_belly, if (showBelly) View.VISIBLE else View.GONE)
            val full = PetEngine.fullness(s, now)
            v.setProgressBar(R.id.pet_belly, 100, Math.round(full * 100).toInt(), false)
            if (Build.VERSION.SDK_INT >= 31) {
                v.setColorStateList(R.id.pet_belly, "setProgressTintList", ColorStateList.valueOf(context.getColor(bellyColor(full))))
            }

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }

        fun bellyColor(fullness: Double): Int = when {
            fullness > 0.5 -> R.color.ok
            fullness > 0.0 -> R.color.warn
            else -> R.color.bad
        }

        fun stateColor(s: PetState, st: Status): Int = when (st) {
            Status.EGG -> R.color.ink_soft
            Status.HAPPY -> if (s.pet.ciFailing) R.color.warn else R.color.ok
            Status.HUNGRY -> R.color.warn
            Status.MONSTER -> R.color.monster
            Status.DEAD -> R.color.ink_soft
        }
    }
}
