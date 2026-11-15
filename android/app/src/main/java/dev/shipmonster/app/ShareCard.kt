package dev.shipmonster.app

import android.content.Context
import dev.shipmonster.app.core.Engine
import dev.shipmonster.app.core.Game
import dev.shipmonster.app.core.Monster
import dev.shipmonster.app.core.Mood
import dev.shipmonster.app.core.Rules

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Draws a square image of the pet, or of a grave, and hands it to the system share sheet. */
object ShareCard {
    private const val SIZE = 1080
    private const val REPO = "github.com/Maneek21/ship-monster"

    class Card(val title: String, val line: String, val sprite: Bitmap, val dark: Boolean)

    /** What to say for the pet as it is now, or for a grave when one is given. */
    /** A card for one monster, or for a hall of fame entry when [hall] is given. */
    fun cardFor(c: Context, g: Game, m: Monster?, hall: String?): Card {
        if (hall != null || m == null) {
            val stone = Art.scaled(Art.frame(c, "gravelkin", 5, Art.STONE), 12)
            return Card("Hall of fame", hall ?: "Nothing here yet.", stone, true)
        }
        val feral = Engine.mood(g) == Mood.FERAL && m.id in g.party
        val sprite = Art.scaled(Art.frame(c, m.species, m.stage, Art.variantFor(g, m, false)), 12)
        val stars = if (m.stars > 0) " ${m.stars}-star ${m.sp.rarity.label.lowercase()}." else " ${m.sp.rarity.label}."
        return when {
            m.stone -> Card("${m.name} turned to stone", "My horde fell because I stopped shipping. A gem brings it back.", sprite, true)
            feral -> Card("${m.name} went feral", "No ship in ${Fmt.hours((g.idleMs / Rules.H).toInt())}. My apps are locked until I push.", sprite, true)
            else -> Card(m.title, "${g.streak}-day shipping streak.$stars Raised on ${g.pushes} pushes and ${g.merges} merged pull requests.", sprite, false)
        }
    }

    fun render(card: Card): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // the den, with a faint dot texture
        cv.drawColor(0xFF201634.toInt())
        paint.color = 0x14FFFFFF
        var y = 8f
        while (y < SIZE) {
            var x = 8f
            while (x < SIZE) {
                cv.drawCircle(x, y, 2.5f, paint)
                x += 16f
            }
            y += 16f
        }

        val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFF3E6.toInt(); typeface = mono; textSize = 54f; letterSpacing = 0.08f
        }
        cv.drawText("SHIP MONSTER", 70f, 118f, head)

        // screen
        paint.color = 0xFF2A1F3D.toInt()
        cv.drawRoundRect(RectF(60f, 160f, 1020f, 940f), 40f, 40f, paint)
        paint.color = if (card.dark) 0xFF3A0F2A.toInt() else 0xFFFFF8E7.toInt()
        cv.drawRoundRect(RectF(90f, 190f, 990f, 910f), 26f, 26f, paint)

        val sprite = card.sprite
        cv.drawBitmap(sprite, (SIZE - sprite.width) / 2f, 212f, null)

        val ink = if (card.dark) 0xFFF6EEFF.toInt() else 0xFF231A2E.toInt()
        val soft = if (card.dark) 0xFFC9B6EE.toInt() else 0xFF655B72.toInt()
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = mono; textSize = 62f }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = 40f }
        val width = 820
        val titleLayout = StaticLayout.Builder.obtain(card.title, 0, card.title.length, title, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).build()
        val bodyLayout = StaticLayout.Builder.obtain(card.line, 0, card.line.length, body, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(6f, 1f).setMaxLines(4).build()
        cv.save()
        cv.translate((SIZE - width) / 2f, 628f)
        titleLayout.draw(cv)
        cv.translate(0f, titleLayout.height + 16f)
        bodyLayout.draw(cv)
        cv.restore()

        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFF3E6.toInt(); typeface = Typeface.MONOSPACE; textSize = 34f
        }
        cv.drawText(REPO, 70f, 1012f, foot)
        return bmp
    }

    fun share(activity: Activity, monsterId: Int, hall: String? = null) {
        try {
            val g = GameStore.load(activity)
            val card = cardFor(activity, g, g.monster(monsterId) ?: g.lead, hall)
            val dir = File(activity.cacheDir, "share")
            dir.mkdirs()
            val file = File(dir, "ship-monster.png")
            FileOutputStream(file).use { render(card).compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, "${card.title}. ${card.line} #ShipMonster")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(null, uri)
            activity.startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(activity, "Could not build the share image.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
