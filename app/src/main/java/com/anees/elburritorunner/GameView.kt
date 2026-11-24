package com.anees.elburritorunner

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

interface Host {
    fun sfx(type: Int)
    fun haptic()
    fun rewardedReady(): Boolean
    fun showRewarded(onReward: () -> Unit)
    fun showInterstitial()
}

private fun col(h: String): Int = Color.parseColor(h)
private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
private fun lerpC(a: Int, b: Int, f: Float): Int = Color.rgb(
    lerp(Color.red(a).toFloat(), Color.red(b).toFloat(), f).toInt(),
    lerp(Color.green(a).toFloat(), Color.green(b).toFloat(), f).toInt(),
    lerp(Color.blue(a).toFloat(), Color.blue(b).toFloat(), f).toInt()
)
private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)

class Skin(val name: String, val price: Int, val body: Int, val belly: Int, val inner: Int, val hair: Int, val band: Int)

val SKINS = listOf(
    Skin("Classic", 0, col("#6F6F78"), col("#EFE2C8"), col("#CDBDB5"), col("#2E2E33"), col("#8EC9F0")),
    Skin("Golden", 150, col("#C9962E"), col("#FFF1C9"), col("#F2D7A0"), col("#6B4A12"), col("#FFB300")),
    Skin("Rose", 250, col("#D98BA8"), col("#FFE6EE"), col("#F7C6D7"), col("#7A3350"), col("#FF8FB1")),
    Skin("Midnight", 400, col("#2E3A59"), col("#B8C4E6"), col("#6F7FAE"), col("#10162B"), col("#5C6BC0")),
    Skin("Mint", 400, col("#5DBB9A"), col("#E3FFF3"), col("#A8E6CF"), col("#1F6B50"), col("#26A69A")),
    Skin("Galaxy", 800, col("#6A3FA0"), col("#E6D4FF"), col("#B58AE8"), col("#2A1250"), col("#E040FB"))
)

// obstacle types: 0 cactus, 1 double cactus, 2 rock, 3 bird (slide under or jump over)
class Obs(val type: Int, var x: Float, val w: Float, val h: Float, val y: Float, val seed: Float)
class Coin(var x: Float, var y: Float, val ph: Float)
class Pow(val type: Int, var x: Float, val y: Float)   // 0 shield, 1 magnet, 2 double coins
class Part(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val max: Float, val color: Int, val size: Float)
class Btn(val id: Int, val r: RectF)

class GameView(ctx: Context, private val host: Host) : View(ctx), Choreographer.FrameCallback {

    private enum class St { MENU, PLAY, PAUSE, DEAD, SHOP }

    private companion object {
        const val B_PLAY = 1; const val B_SHOP = 2; const val B_SOUND = 3; const val B_PAUSE = 4
        const val B_RESUME = 5; const val B_MENU = 6; const val B_RETRY = 7; const val B_REVIVE = 8
        const val B_DOUBLE = 9; const val B_BACK = 10; const val B_SKIN = 100
    }

    // ── persistence ──
    private val prefs: SharedPreferences = ctx.getSharedPreferences("elburrito", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)
    private var coinsTotal = prefs.getInt("coins", 0)
    private var skinSel = prefs.getInt("skin", 0)
    private var owned: MutableSet<Int> = (prefs.getString("owned", "0") ?: "0").split(",").mapNotNull { it.toIntOrNull() }.toMutableSet()
    private var soundOn = prefs.getBoolean("sound", true)
    private var games = prefs.getInt("games", 0)

    // ── drawing ──
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()
    private lateinit var cv: Canvas
    private val btns = ArrayList<Btn>()
    private var W = 0f
    private var H = 0f
    private var u = 1f
    private var gy = 0f

    // ── state ──
    private var st = St.MENU
    private var anim = 0f
    private var worldX = 0f
    private var runStart = 0f
    private var speed = 0f
    private var runPhase = 0f
    private var t = 0f
    private var bx = 0f
    private var hy = 0f
    private var vy = 0f
    private var pgY = 0f
    private var onGround = true
    private var jumps = 0
    private var slideT = 0f
    private var invuln = 0f
    private var shield = false
    private var magnetT = 0f
    private var doubleT = 0f
    private var coinsRun = 0
    private var banked = 0
    private var spawnDist = 0f
    private var puTimer = 0f
    private var revived = false
    private var doubled = false
    private var newBest = false
    private var deadT = 0f
    private var shake = 0f
    private var msg = ""
    private var msgT = 0f
    private var lastInter = 0
    private var downY = 0f
    private var swiped = false
    private var lastNs = 0L
    private var running = false

    private val obs = ArrayList<Obs>()
    private val coins = ArrayList<Coin>()
    private val pows = ArrayList<Pow>()
    private val parts = ArrayList<Part>()
    private val starX = FloatArray(40) { Random.nextFloat() }
    private val starY = FloatArray(40) { Random.nextFloat() * 0.55f }

    private val skyP = floatArrayOf(0f, 0.30f, 0.45f, 0.80f, 0.92f, 1f)
    private val skyTop = intArrayOf(col("#6EC6FF"), col("#FF9A62"), col("#141B3C"), col("#141B3C"), col("#FF9A62"), col("#6EC6FF"))
    private val skyBot = intArrayOf(col("#D8F1FF"), col("#FFD6A0"), col("#2B3A6B"), col("#2B3A6B"), col("#FFD6A0"), col("#D8F1FF"))
    private val nightV = floatArrayOf(0f, 0.2f, 1f, 1f, 0.2f, 0f)

    init {
        val today = (System.currentTimeMillis() / 86400000L).toInt()
        if (prefs.getInt("day", 0) != today) {
            coinsTotal += 50
            prefs.edit().putInt("day", today).apply()
            save()
            toast("Daily bonus: +50 coins")
        }
    }

    // ───────────────────────── lifecycle ─────────────────────────
    fun start() {
        if (running) return
        running = true; lastNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun pauseIfPlaying() { if (st == St.PLAY) st = St.PAUSE }

    fun onBack(): Boolean {
        return when (st) {
            St.PLAY -> { st = St.PAUSE; true }
            St.PAUSE -> { st = St.PLAY; true }
            St.SHOP -> { st = St.MENU; true }
            St.DEAD -> { leaveToMenu(); true }
            St.MENU -> false
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNs == 0L) 0.016f else ((frameTimeNanos - lastNs) / 1_000_000_000f).coerceAtMost(0.033f)
        lastNs = frameTimeNanos
        update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        W = w.toFloat(); H = h.toFloat()
        u = min(H / 360f, W / 640f)
        gy = H * 0.80f
        bx = max(W * 0.2f, 100f * u)
        if (st != St.PLAY) { hy = gy; pgY = gy }
    }

    private fun save() {
        prefs.edit().putInt("best", best).putInt("coins", coinsTotal).putInt("skin", skinSel)
            .putString("owned", owned.joinToString(",")).putBoolean("sound", soundOn).putInt("games", games).apply()
    }

    private fun toast(s: String) { msg = s; msgT = 2.4f }
    private fun sfx(t: Int) { if (soundOn) host.sfx(t) }
    private fun score(): Int = max(0f, (worldX - runStart) / (u * 10f)).toInt()

    // ───────────────────────── game flow ─────────────────────────
    private fun startRun() {
        st = St.PLAY
        t = 0f; runStart = worldX; coinsRun = 0; banked = 0
        hy = gy; vy = 0f; pgY = gy; onGround = true; jumps = 0; slideT = 0f; invuln = 0f
        shield = false; magnetT = 0f; doubleT = 0f
        obs.clear(); coins.clear(); pows.clear(); parts.clear()
        spawnDist = 520f * u; puTimer = 14f
        revived = false; doubled = false; newBest = false; shake = 0f
        speed = 250f * u
    }

    private fun die() {
        st = St.DEAD; deadT = 0f; shake = 0.45f
        host.haptic(); sfx(2)
        games++
        val sc = score()
        if (sc > best) { best = sc; newBest = true }
        coinsTotal += coinsRun - banked; banked = coinsRun
        save()
        burst(bx, hy - 34f * u, 26, SKINS[skinSel].body, 320f * u)
        burst(bx, hy - 34f * u, 12, col("#FFC83D"), 260f * u)
    }

    private fun revive() {
        st = St.PLAY
        invuln = 2.2f; hy = gy; vy = 0f; onGround = true; jumps = 0; slideT = 0f
        obs.removeAll { it.x < W * 0.95f }
        revived = true
        toast("Back in the game!")
    }

    private fun leaveToMenu() { maybeInterstitial(); st = St.MENU }

    private fun maybeInterstitial() {
        if (games > 0 && games % 3 == 0 && games != lastInter) { lastInter = games; host.showInterstitial() }
    }

    private fun jump() {
        if (onGround) {
            vy = -720f * u; onGround = false; jumps = 1
            burst(bx, gy, 5, col("#C9A66B"), 70f * u); sfx(0)
        } else if (jumps < 2) {
            vy = -640f * u; jumps = 2
            burst(bx, hy - 10f * u, 8, Color.WHITE, 90f * u); sfx(0)
        }
    }

    private fun slide() {
        if (!onGround) vy = max(vy, 1100f * u)
        slideT = 0.6f
    }

    private fun burst(x: Float, y: Float, n: Int, color: Int, sp: Float) {
        repeat(n) {
            val a = rnd(0f, 6.283f)
            val v = rnd(0.3f, 1f) * sp
            val l = rnd(0.35f, 0.85f)
            parts.add(Part(x, y, cos(a) * v, sin(a) * v - sp * 0.25f, l, l, color, rnd(2f, 5f) * u))
        }
    }

    // ───────────────────────── update ─────────────────────────
    private fun update(dt: Float) {
        anim += dt
        if (msgT > 0f) msgT -= dt
        if (shake > 0f) shake -= dt
        when (st) {
            St.PLAY -> updatePlay(dt)
            St.MENU, St.SHOP -> {
                speed = 150f * u
                worldX += speed * dt
                runPhase += dt * 14f * 0.6f
                updateParts(dt)
            }
            St.PAUSE -> {}
            St.DEAD -> { deadT += dt; updateParts(dt) }
        }
    }

    private fun updateParts(dt: Float) {
        for (i in parts.indices.reversed()) {
            val q = parts[i]
            q.life -= dt
            if (q.life <= 0f) { parts.removeAt(i); continue }
            q.vy += 900f * u * dt
            q.x += q.vx * dt; q.y += q.vy * dt
        }
    }

    private fun updatePlay(dt: Float) {
        t += dt
        speed = u * min(250f + t * 5.5f, 640f)
        val d = speed * dt
        worldX += d
        runPhase += dt * 14f * (speed / (250f * u))

        vy += 2400f * u * dt
        hy += vy * dt
        if (hy >= gy) {
            hy = gy; vy = 0f
            if (!onGround) { onGround = true; jumps = 0; burst(bx, gy, 6, col("#C9A66B"), 70f * u) }
        }
        if (slideT > 0f) slideT -= dt
        if (invuln > 0f) invuln -= dt
        if (magnetT > 0f) magnetT -= dt
        if (doubleT > 0f) doubleT -= dt
        pgY += (hy - pgY) * min(1f, dt * 7f)
        if (pgY > gy) pgY = gy
        if (onGround && Random.nextFloat() < 0.25f) {
            parts.add(Part(bx - 18f * u, gy - 2f * u, -speed * 0.2f, rnd(-60f, -20f) * u, 0.4f, 0.4f, col("#C9A66B"), rnd(2f, 4f) * u))
        }

        spawnDist -= d
        if (spawnDist <= 0f) spawn()
        puTimer -= dt
        if (puTimer <= 0f) {
            pows.add(Pow(Random.nextInt(3), W + 60f * u + spawnDist * 0.5f, gy - 55f * u))
            puTimer = rnd(18f, 30f)
        }

        for (o in obs) o.x -= d
        for (c in coins) c.x -= d
        for (w in pows) w.x -= d
        obs.removeAll { it.x + it.w < -80f * u }
        coins.removeAll { it.x < -60f * u }
        pows.removeAll { it.x < -60f * u }

        // collision with obstacles
        if (invuln <= 0f) {
            val bh = if (slideT > 0f && onGround) 24f * u else 50f * u
            val l1 = bx - 16f * u
            val r1 = bx + 18f * u
            val t1 = hy - bh + 6f * u
            val b1 = hy - 2f * u
            var hitObs: Obs? = null
            for (o in obs) {
                val l2 = o.x + o.w * 0.15f
                val r2 = o.x + o.w * 0.85f
                val t2 = o.y - o.h
                val b2 = o.y
                if (r1 > l2 && l1 < r2 && b1 > t2 && t1 < b2) { hitObs = o; break }
            }
            if (hitObs != null) {
                if (shield) {
                    shield = false; invuln = 1.3f; obs.remove(hitObs)
                    burst(bx, hy - 34f * u, 18, col("#9EE7FF"), 260f * u)
                    shake = 0.2f; sfx(3)
                } else {
                    die(); return
                }
            }
        }

        // coins
        val cx = bx
        val cy = hy - 30f * u
        val itC = coins.iterator()
        while (itC.hasNext()) {
            val c = itC.next()
            var dx = cx - c.x
            var dy = cy - c.y
            var dist = sqrt(dx * dx + dy * dy)
            if (magnetT > 0f && dist < 230f * u && dist > 1f) {
                c.x += dx / dist * 1000f * u * dt
                c.y += dy / dist * 1000f * u * dt
                dx = cx - c.x; dy = cy - c.y
                dist = sqrt(dx * dx + dy * dy)
            }
            if (dist < 34f * u) {
                coinsRun += if (doubleT > 0f) 2 else 1
                burst(c.x, c.y, 4, col("#FFD84D"), 120f * u)
                sfx(1)
                itC.remove()
            }
        }
        // power-ups
        val ip = pows.iterator()
        while (ip.hasNext()) {
            val w = ip.next()
            val dx = cx - w.x
            val dy = cy - w.y
            if (sqrt(dx * dx + dy * dy) < 40f * u) {
                when (w.type) {
                    0 -> { shield = true; toast("Penguin shield!") }
                    1 -> { magnetT = 9f; toast("Coin magnet!") }
                    else -> { doubleT = 10f; toast("Double coins!") }
                }
                burst(w.x, w.y, 14, Color.WHITE, 220f * u)
                sfx(1)
                ip.remove()
            }
        }
        updateParts(dt)
    }

    private fun spawn() {
        val x = W + 60f * u
        val bird = t > 12f && Random.nextFloat() < 0.28f
        val r = Random.nextFloat()
        val o: Obs = when {
            bird -> Obs(3, x, 44f * u, 28f * u, gy - 34f * u, Random.nextFloat() * 6f)
            r < 0.45f -> Obs(0, x, 26f * u, rnd(44f, 72f) * u, gy, 0f)
            r < 0.65f -> Obs(1, x, 60f * u, rnd(46f, 64f) * u, gy, 0f)
            else -> Obs(2, x, rnd(40f, 54f) * u, rnd(26f, 36f) * u, gy, 0f)
        }
        obs.add(o)
        spawnDist = speed * rnd(0.9f, 1.5f) + o.w + 90f * u
        if (Random.nextFloat() < 0.65f) {
            if (o.type == 3) {
                for (i in 0..3) coins.add(Coin(x + i * 30f * u, gy - 16f * u, i.toFloat()))
            } else {
                for (i in 0..4) {
                    val a = PI.toFloat() * i / 4f
                    coins.add(Coin(x + o.w / 2f + (i - 2) * 34f * u, gy - o.h - 22f * u - 40f * u * sin(a), i.toFloat()))
                }
            }
        }
        if (Random.nextFloat() < 0.5f) {
            val cx = x + spawnDist * 0.5f
            for (i in 0..3) coins.add(Coin(cx + i * 30f * u, gy - 20f * u, i.toFloat()))
        }
    }

    // ───────────────────────── touch ─────────────────────────
    private fun hit(x: Float, y: Float): Btn? = btns.firstOrNull { it.r.contains(x, y) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y; swiped = false
                if (st == St.PLAY) {
                    val b = hit(e.x, e.y)
                    if (b != null && b.id == B_PAUSE) act(b.id) else jump()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (st == St.PLAY && !swiped && e.y - downY > 60f * u) { swiped = true; slide() }
            }
            MotionEvent.ACTION_UP -> {
                if (st != St.PLAY) hit(e.x, e.y)?.let { act(it.id) }
            }
        }
        return true
    }

    private fun act(id: Int) {
        when {
            id == B_PLAY -> startRun()
            id == B_RETRY -> { maybeInterstitial(); startRun() }
            id == B_SHOP -> st = St.SHOP
            id == B_BACK -> st = St.MENU
            id == B_SOUND -> { soundOn = !soundOn; save() }
            id == B_PAUSE -> st = St.PAUSE
            id == B_RESUME -> st = St.PLAY
            id == B_MENU -> { if (st == St.DEAD) leaveToMenu() else st = St.MENU }
            id == B_REVIVE -> host.showRewarded { revive() }
            id == B_DOUBLE -> host.showRewarded {
                coinsTotal += coinsRun; doubled = true; save()
                toast("+$coinsRun bonus coins!")
            }
            id >= B_SKIN -> {
                val i = id - B_SKIN
                val sk = SKINS[i]
                if (owned.contains(i)) { skinSel = i; save() }
                else if (coinsTotal >= sk.price) { coinsTotal -= sk.price; owned.add(i); skinSel = i; save(); toast("${sk.name} unlocked!") }
                else toast("Need ${sk.price - coinsTotal} more coins")
            }
        }
    }

    // ───────────────────────── draw helpers ─────────────────────────
    private fun circ(x: Float, y: Float, r: Float, color: Int) { p.style = Paint.Style.FILL; p.color = color; cv.drawCircle(x, y, r, p) }
    private fun oval(l: Float, t: Float, r: Float, b: Float, color: Int) { rf.set(l, t, r, b); p.style = Paint.Style.FILL; p.color = color; cv.drawOval(rf, p) }
    private fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) { rf.set(l, t, r, b); p.style = Paint.Style.FILL; p.color = color; cv.drawRoundRect(rf, rad, rad, p) }
    private fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int) {
        path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        p.style = Paint.Style.FILL; p.color = color; cv.drawPath(path, p)
    }

    private fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = size; p.textAlign = align
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.16f; p.strokeJoin = Paint.Join.ROUND
        p.color = Color.argb(190, 20, 20, 40); cv.drawText(s, x, y, p)
        p.style = Paint.Style.FILL; p.color = color; cv.drawText(s, x, y, p)
    }

    private fun button(id: Int, label: String, cx: Float, cy: Float, w: Float, h: Float, color: Int) {
        rf.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        p.style = Paint.Style.FILL
        p.color = Color.argb(90, 0, 0, 0)
        cv.drawRoundRect(rf.left, rf.top + 4f * u, rf.right, rf.bottom + 4f * u, 14f * u, 14f * u, p)
        p.color = color
        cv.drawRoundRect(rf, 14f * u, 14f * u, p)
        p.color = Color.argb(70, 255, 255, 255)
        cv.drawRoundRect(rf.left + 3f * u, rf.top + 3f * u, rf.right - 3f * u, rf.top + h * 0.45f, 11f * u, 11f * u, p)
        btns.add(Btn(id, RectF(rf)))
        text(label, cx, cy + h * 0.17f, h * 0.42f, Color.WHITE)
    }

    private fun seg(ph: Float): Int { var i = 0; while (i < skyP.size - 2 && ph > skyP[i + 1]) i++; return i }
    private fun segF(ph: Float, i: Int) = ((ph - skyP[i]) / (skyP[i + 1] - skyP[i])).coerceIn(0f, 1f)

    // ───────────────────────── draw ─────────────────────────
    override fun onDraw(canvas: Canvas) {
        cv = canvas
        btns.clear()
        canvas.save()
        if (shake > 0f) canvas.translate(rnd(-1f, 1f) * shake * 22f * u, rnd(-1f, 1f) * shake * 22f * u)
        drawWorld()
        canvas.restore()
        when (st) {
            St.MENU -> drawMenu()
            St.PLAY -> drawHud()
            St.PAUSE -> { drawHud(); drawPause() }
            St.DEAD -> { drawHud(); drawDead() }
            St.SHOP -> drawShop()
        }
        if (msgT > 0f) text(msg, W / 2f, H * 0.14f, 22f * u, col("#FFE066"))
    }

    private fun ridge(off: Float, base: Float, amp: Float, wl: Float, color: Int) {
        path.reset(); path.moveTo(0f, gy)
        var x = 0f
        while (x <= W + 30f) {
            val y = gy - base - amp * (0.5f + 0.35f * sin((x + off) / wl) + 0.15f * sin((x + off) / (wl * 0.43f)))
            path.lineTo(x, y); x += 24f
        }
        path.lineTo(W + 30f, gy); path.close()
        p.style = Paint.Style.FILL; p.color = color; cv.drawPath(path, p)
    }

    private fun drawWorld() {
        val ph = (worldX / (u * 4000f)) % 1f
        val i = seg(ph); val f = segF(ph, i)
        val top = lerpC(skyTop[i], skyTop[i + 1], f)
        val bot = lerpC(skyBot[i], skyBot[i + 1], f)
        val night = lerp(nightV[i], nightV[i + 1], f)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, gy, top, bot, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, W, H, p)
        p.shader = null
        // stars
        if (night > 0.3f) {
            for (s in starX.indices) {
                val tw = 0.5f + 0.5f * sin(anim * 2f + s)
                p.color = Color.WHITE; p.alpha = (night * tw * 255f).toInt().coerceIn(0, 255)
                cv.drawCircle(starX[s] * W, starY[s] * gy, (1f + (s % 3)) * u * 0.8f, p)
            }
        }
        // sun / moon
        if (night < 0.5f) {
            circ(W * 0.8f, H * 0.2f, 26f * u, Color.argb(70, 255, 230, 120))
            circ(W * 0.8f, H * 0.2f, 18f * u, col("#FFE066"))
        } else {
            circ(W * 0.8f, H * 0.2f, 18f * u, col("#F4F1DE"))
            circ(W * 0.8f - 5f * u, H * 0.2f - 4f * u, 3f * u, col("#D9D5C0"))
            circ(W * 0.8f + 6f * u, H * 0.2f + 5f * u, 2.4f * u, col("#D9D5C0"))
        }
        // clouds
        val cloudA = (255f * (1f - night * 0.75f)).toInt()
        for (k in 0..4) {
            val span = W + 240f * u
            val cx = (((k * 210f * u - anim * (9f + k * 4f) * u) % span) + span) % span - 120f * u
            val cy = H * (0.1f + 0.07f * (k % 3))
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.alpha = cloudA
            cv.drawCircle(cx, cy, 14f * u, p); cv.drawCircle(cx + 16f * u, cy + 3f * u, 11f * u, p)
            cv.drawCircle(cx - 16f * u, cy + 4f * u, 10f * u, p)
        }
        ridge(worldX * 0.08f, 60f * u, 90f * u, 120f * u, lerpC(col("#9BB8D3"), col("#1F2A52"), night))
        ridge(worldX * 0.25f, 10f * u, 45f * u, 70f * u, lerpC(col("#7BC47F"), col("#16352F"), night))
        // ground
        p.style = Paint.Style.FILL
        p.color = lerpC(col("#D8B77A"), col("#4A3F3A"), night)
        cv.drawRect(0f, gy, W, H, p)
        p.color = lerpC(col("#B98F4E"), col("#2E2724"), night)
        cv.drawRect(0f, gy, W, gy + 6f * u, p)
        val span = W + 60f * u
        for (k in 0..15) {
            val x = (((k * 83.7f * u - worldX) % span) + span) % span - 30f * u
            val y = gy + (16f + (k % 3) * 16f) * u
            rr(x, y, x + 10f * u, y + 3f * u, 2f * u, lerpC(col("#B98F4E"), col("#2E2724"), night))
        }
        // objects
        for (o in obs) drawObs(o)
        for (c in coins) {
            val w = abs(cos(anim * 5f + c.ph)) * 8f * u + 2f * u
            oval(c.x - w, c.y - 9f * u, c.x + w, c.y + 9f * u, col("#FFC83D"))
            oval(c.x - w * 0.55f, c.y - 6f * u, c.x + w * 0.55f, c.y + 6f * u, col("#FFE58A"))
        }
        for (w in pows) drawPow(w)
        drawPenguin(bx - 64f * u, pgY, 0.8f * u, runPhase, !onGround)
        drawHero()
        for (q in parts) {
            p.style = Paint.Style.FILL; p.color = q.color
            p.alpha = (255f * (q.life / q.max)).toInt().coerceIn(0, 255)
            cv.drawCircle(q.x, q.y, q.size, p)
        }
        p.alpha = 255
    }

    private fun drawHero() {
        if (st == St.DEAD) return
        val blink = invuln > 0f && ((anim * 18f).toInt() % 2 == 0)
        if (!blink) drawBurrito(bx, hy, 0.6f * u, SKINS[skinSel], runPhase, !onGround, slideT > 0f && onGround)
        if (shield) {
            circ(bx, hy - 36f * u, 52f * u, Color.argb(55, 120, 220, 255))
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f * u; p.color = Color.argb(200, 160, 235, 255)
            cv.drawCircle(bx, hy - 36f * u, 52f * u, p)
            p.style = Paint.Style.FILL
        }
    }

    // Baby donkey: bipedal, chubby, diaper with blue waistband, tall ears, hair tuft
    private fun drawBurrito(x: Float, yb: Float, s: Float, sk: Skin, ph: Float, air: Boolean, slide: Boolean) {
        val c = cv
        c.save()
        if (slide) c.scale(1.12f, 0.62f, x, yb)
        val sw = if (air) 0f else sin(ph) * 8f * s
        val tuck = if (air) 5f * s else 0f
        val dark = lerpC(sk.body, Color.BLACK, 0.25f)
        val eye = col("#3A2418")
        val earW = if (air) -4f * s else sin(ph) * 2.5f * s
        // tail
        val tw = sin(ph * 0.7f) * 5f * s
        oval(x - 31f * s, yb - 30f * s + tw, x - 18f * s, yb - 21f * s + tw, sk.hair)
        // far leg + far arm
        rr(x - 11f * s - sw, yb - 12f * s, x - 3f * s - sw, yb - tuck, 3f * s, dark)
        rr(x - 12f * s - sw, yb - 5f * s - tuck, x - 2f * s - sw, yb - tuck, 2.5f * s, sk.hair)
        oval(x - 20f * s, yb - 46f * s + sw * 0.5f, x - 11f * s, yb - 30f * s + sw * 0.5f, dark)
        // body, belly, diaper
        oval(x - 21f * s, yb - 58f * s, x + 21f * s, yb - 14f * s, sk.body)
        oval(x - 4f * s, yb - 52f * s, x + 19f * s, yb - 20f * s, sk.belly)
        rr(x - 20f * s, yb - 24f * s, x + 20f * s, yb - 6f * s, 7f * s, Color.WHITE)
        rr(x - 20f * s, yb - 26f * s, x + 20f * s, yb - 20f * s, 3f * s, sk.band)
        // near leg + near arm
        rr(x + 3f * s + sw, yb - 10f * s, x + 12f * s + sw, yb - tuck, 3f * s, sk.body)
        rr(x + 2f * s + sw, yb - 5f * s - tuck, x + 13f * s + sw, yb - tuck, 2.5f * s, sk.hair)
        oval(x + 12f * s, yb - 46f * s - sw * 0.5f, x + 21f * s, yb - 30f * s - sw * 0.5f, sk.body)
        // ears (behind head)
        tri(x - 12f * s, yb - 84f * s, x - 17f * s, yb - 118f * s + earW, x - 2f * s, yb - 88f * s, sk.body)
        tri(x - 11f * s, yb - 88f * s, x - 15f * s, yb - 110f * s + earW, x - 5f * s, yb - 90f * s, sk.inner)
        tri(x + 2f * s, yb - 88f * s, x + 8f * s, yb - 122f * s + earW, x + 16f * s, yb - 84f * s, sk.body)
        tri(x + 4f * s, yb - 91f * s, x + 8f * s, yb - 113f * s + earW, x + 13f * s, yb - 87f * s, sk.inner)
        // head
        circ(x + 3f * s, yb - 70f * s, 22f * s, sk.body)
        oval(x + 9f * s, yb - 70f * s, x + 31f * s, yb - 54f * s, sk.belly)
        circ(x + 28f * s, yb - 65f * s, 3.4f * s, col("#B79A9F"))
        circ(x + 9f * s, yb - 75f * s, 5.2f * s, eye)
        circ(x + 10.8f * s, yb - 77f * s, 1.8f * s, Color.WHITE)
        circ(x - 4f * s, yb - 75f * s, 4.6f * s, eye)
        circ(x - 2.6f * s, yb - 77f * s, 1.6f * s, Color.WHITE)
        circ(x + 8f * s, yb - 62f * s, 3f * s, Color.argb(80, 255, 120, 140))
        // messy hair tuft
        tri(x - 6f * s, yb - 90f * s, x - 1f * s, yb - 103f * s, x + 3f * s, yb - 90f * s, sk.hair)
        tri(x - 1f * s, yb - 91f * s, x + 6f * s, yb - 105f * s, x + 9f * s, yb - 90f * s, sk.hair)
        tri(x + 4f * s, yb - 91f * s, x + 13f * s, yb - 99f * s, x + 14f * s, yb - 89f * s, sk.hair)
        c.restore()
    }

    // Baby penguin: red scarf + blue earmuffs, bipedal
    private fun drawPenguin(x: Float, yb: Float, s: Float, ph: Float, air: Boolean) {
        val sw = if (air) 0f else sin(ph + 1.5f) * 4f * s
        val navy = col("#1B2440")
        val orange = col("#FF9F1C")
        val blue = col("#2F80ED")
        val red = col("#E63946")
        oval(x - 9f * s + sw, yb - 4f * s, x - 1f * s + sw, yb, orange)
        oval(x + 2f * s - sw, yb - 4f * s, x + 10f * s - sw, yb, orange)
        oval(x - 12f * s, yb - 32f * s, x + 12f * s, yb - 3f * s, navy)
        oval(x - 6f * s, yb - 28f * s, x + 11f * s, yb - 5f * s, Color.WHITE)
        oval(x - 15f * s, yb - 26f * s + sw, x - 8f * s, yb - 12f * s + sw, navy)
        circ(x + 1f * s, yb - 40f * s, 10.5f * s, navy)
        oval(x + 2f * s, yb - 44f * s, x + 12f * s, yb - 34f * s, Color.WHITE)
        tri(x + 10f * s, yb - 41f * s, x + 18f * s, yb - 38f * s, x + 10f * s, yb - 36f * s, orange)
        circ(x + 7f * s, yb - 43f * s, 2.4f * s, Color.BLACK)
        circ(x + 7.8f * s, yb - 43.8f * s, 0.8f * s, Color.WHITE)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * s; p.color = blue
        rf.set(x - 10f * s, yb - 52f * s, x + 12f * s, yb - 30f * s)
        cv.drawArc(rf, 200f, 140f, false, p)
        p.style = Paint.Style.FILL
        circ(x - 10f * s, yb - 41f * s, 4.6f * s, blue)
        circ(x + 11f * s, yb - 41f * s, 4.6f * s, blue)
        rr(x - 10f * s, yb - 31f * s, x + 12f * s, yb - 26f * s, 2.5f * s, red)
        rr(x - 12f * s, yb - 28f * s, x - 6f * s, yb - 14f * s + sw * 0.3f, 2.5f * s, red)
    }

    private fun drawCactus(x: Float, w: Float, h: Float) {
        val g = col("#3E9B4F"); val gd = col("#2E7D3E")
        val yb = gy
        rr(x + w * 0.3f, yb - h, x + w * 0.7f, yb, w * 0.2f, g)
        rr(x, yb - h * 0.62f, x + w * 0.45f, yb - h * 0.48f, w * 0.1f, g)
        rr(x, yb - h * 0.82f, x + w * 0.25f, yb - h * 0.48f, w * 0.12f, g)
        rr(x + w * 0.55f, yb - h * 0.55f, x + w, yb - h * 0.41f, w * 0.1f, g)
        rr(x + w * 0.75f, yb - h * 0.74f, x + w, yb - h * 0.41f, w * 0.12f, g)
        rr(x + w * 0.46f, yb - h * 0.95f, x + w * 0.54f, yb - h * 0.05f, w * 0.04f, gd)
    }

    private fun drawObs(o: Obs) {
        when (o.type) {
            0 -> drawCactus(o.x, o.w, o.h)
            1 -> { drawCactus(o.x, o.w * 0.46f, o.h); drawCactus(o.x + o.w * 0.54f, o.w * 0.46f, o.h * 0.8f) }
            2 -> {
                path.reset()
                path.moveTo(o.x, gy); path.lineTo(o.x + o.w * 0.1f, gy - o.h * 0.6f)
                path.lineTo(o.x + o.w * 0.4f, gy - o.h); path.lineTo(o.x + o.w * 0.75f, gy - o.h * 0.8f)
                path.lineTo(o.x + o.w, gy); path.close()
                p.style = Paint.Style.FILL; p.color = col("#8D8D95"); cv.drawPath(path, p)
                tri(o.x + o.w * 0.4f, gy - o.h, o.x + o.w * 0.75f, gy - o.h * 0.8f, o.x + o.w * 0.55f, gy - o.h * 0.5f, col("#A9A9B2"))
            }
            else -> {
                val cx = o.x + o.w / 2f
                val cy = o.y - o.h / 2f + sin(anim * 5f + o.seed) * 3f * u
                val wing = sin(anim * 14f + o.seed) * o.h * 0.9f
                tri(cx + o.w * 0.3f, cy, cx + o.w * 0.55f, cy - o.h * 0.25f, cx + o.w * 0.55f, cy + o.h * 0.2f, col("#6C3BAA"))
                oval(cx - o.w * 0.4f, cy - o.h * 0.3f, cx + o.w * 0.4f, cy + o.h * 0.3f, col("#6C3BAA"))
                tri(cx - o.w * 0.1f, cy, cx + o.w * 0.15f, cy + wing, cx + o.w * 0.32f, cy, col("#8E5BD6"))
                circ(cx - o.w * 0.42f, cy - o.h * 0.05f, o.h * 0.28f, col("#6C3BAA"))
                tri(cx - o.w * 0.66f, cy - o.h * 0.05f, cx - o.w * 0.5f, cy - o.h * 0.17f, cx - o.w * 0.5f, cy + o.h * 0.07f, col("#FF9F1C"))
                circ(cx - o.w * 0.45f, cy - o.h * 0.1f, o.h * 0.07f, Color.WHITE)
            }
        }
    }

    private fun drawPow(w: Pow) {
        val color = when (w.type) { 0 -> col("#29B6F6"); 1 -> col("#EF5350"); else -> col("#AB47BC") }
        val pulse = 1f + 0.12f * sin(anim * 6f)
        circ(w.x, w.y, 22f * u * pulse, Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)))
        circ(w.x, w.y, 16f * u, Color.WHITE)
        circ(w.x, w.y, 13f * u, color)
        text(when (w.type) { 0 -> "S"; 1 -> "M"; else -> "2x" }, w.x, w.y + 5f * u, 14f * u, Color.WHITE)
    }

    // ───────────────────────── screens ─────────────────────────
    private fun drawHud() {
        text(score().toString(), 18f * u, 44f * u, 34f * u, Color.WHITE, Paint.Align.LEFT)
        circ(26f * u, 68f * u, 8f * u, col("#FFC83D"))
        text(coinsRun.toString(), 42f * u, 76f * u, 22f * u, col("#FFE58A"), Paint.Align.LEFT)
        text("BEST $best", W / 2f, 30f * u, 16f * u, Color.argb(220, 255, 255, 255))
        if (st == St.PLAY) button(B_PAUSE, "II", W - 36f * u, 34f * u, 44f * u, 44f * u, col("#546E7A"))
        var y = 100f * u
        if (shield) { circ(26f * u, y, 10f * u, col("#29B6F6")); text("Shield", 42f * u, y + 6f * u, 16f * u, Color.WHITE, Paint.Align.LEFT); y += 26f * u }
        if (magnetT > 0f) { circ(26f * u, y, 10f * u, col("#EF5350")); text("Magnet ${magnetT.toInt() + 1}s", 42f * u, y + 6f * u, 16f * u, Color.WHITE, Paint.Align.LEFT); y += 26f * u }
        if (doubleT > 0f) { circ(26f * u, y, 10f * u, col("#AB47BC")); text("2x coins ${doubleT.toInt() + 1}s", 42f * u, y + 6f * u, 16f * u, Color.WHITE, Paint.Align.LEFT) }
    }

    private fun drawMenu() {
        text("EL BURRITO", W / 2f, H * 0.22f, 54f * u, col("#FFD54F"))
        text("RUNNER", W / 2f, H * 0.22f + 44f * u, 40f * u, Color.WHITE)
        button(B_PLAY, "PLAY", W / 2f, H * 0.5f, 220f * u, 64f * u, col("#43A047"))
        button(B_SHOP, "SHOP", W / 2f, H * 0.5f + 80f * u, 220f * u, 52f * u, col("#1E88E5"))
        button(B_SOUND, if (soundOn) "Sound: On" else "Sound: Off", W - 84f * u, 34f * u, 140f * u, 40f * u, col("#546E7A"))
        text("Best: $best", 18f * u, 36f * u, 22f * u, Color.WHITE, Paint.Align.LEFT)
        circ(26f * u, 60f * u, 8f * u, col("#FFC83D"))
        text("$coinsTotal", 42f * u, 68f * u, 22f * u, col("#FFE58A"), Paint.Align.LEFT)
        text("Tap: jump / double jump    Swipe down: slide", W / 2f, gy + 40f * u, 15f * u, Color.WHITE)
    }

    private fun drawPause() {
        p.style = Paint.Style.FILL; p.color = Color.argb(130, 0, 0, 0); cv.drawRect(0f, 0f, W, H, p)
        text("PAUSED", W / 2f, H * 0.34f, 48f * u, Color.WHITE)
        button(B_RESUME, "RESUME", W / 2f, H * 0.5f, 220f * u, 60f * u, col("#43A047"))
        button(B_MENU, "MENU", W / 2f, H * 0.5f + 76f * u, 220f * u, 50f * u, col("#546E7A"))
    }

    private fun drawDead() {
        p.style = Paint.Style.FILL; p.color = Color.argb(150, 0, 0, 0); cv.drawRect(0f, 0f, W, H, p)
        val a = min(1f, deadT * 3f)
        val pw = min(W * 0.86f, 560f * u)
        val cy = H * 0.5f - 6f * u
        rf.set(W / 2f - pw / 2f, cy - 118f * u * a, W / 2f + pw / 2f, cy + 100f * u * a)
        p.color = Color.argb(235, 30, 36, 64); cv.drawRoundRect(rf, 22f * u, 22f * u, p)
        if (a < 1f) return
        text("GAME OVER", W / 2f, cy - 80f * u, 36f * u, col("#FF7043"))
        text("Score ${score()}     Best $best", W / 2f, cy - 48f * u, 22f * u, Color.WHITE)
        text(if (newBest) "NEW BEST!   +$coinsRun coins" else "+$coinsRun coins", W / 2f, cy - 22f * u, 19f * u, col("#FFE066"))
        val canRev = !revived && host.rewardedReady()
        val canDbl = !doubled && coinsRun > 0 && host.rewardedReady()
        val bw = 190f * u; val bh = 46f * u
        if (canRev && canDbl) {
            button(B_REVIVE, "Revive (Ad)", W / 2f - 102f * u, cy + 16f * u, bw, bh, col("#8E24AA"))
            button(B_DOUBLE, "2x Coins (Ad)", W / 2f + 102f * u, cy + 16f * u, bw, bh, col("#F9A825"))
        } else if (canRev) button(B_REVIVE, "Revive (Ad)", W / 2f, cy + 16f * u, bw, bh, col("#8E24AA"))
        else if (canDbl) button(B_DOUBLE, "2x Coins (Ad)", W / 2f, cy + 16f * u, bw, bh, col("#F9A825"))
        button(B_RETRY, "RETRY", W / 2f - 102f * u, cy + 70f * u, bw, bh, col("#43A047"))
        button(B_MENU, "MENU", W / 2f + 102f * u, cy + 70f * u, bw, bh, col("#546E7A"))
    }

    private fun drawShop() {
        p.style = Paint.Style.FILL; p.color = Color.argb(175, 10, 14, 30); cv.drawRect(0f, 0f, W, H, p)
        text("SHOP", W / 2f, 40f * u, 34f * u, col("#FFD54F"))
        button(B_BACK, "Back", 64f * u, 30f * u, 100f * u, 40f * u, col("#546E7A"))
        circ(W - 86f * u, 28f * u, 9f * u, col("#FFC83D"))
        text("$coinsTotal", W - 70f * u, 36f * u, 24f * u, col("#FFE58A"), Paint.Align.LEFT)
        val cw = 170f * u; val ch = 135f * u; val gap = 12f * u
        val x0 = W / 2f - (3f * cw + 2f * gap) / 2f
        for (i in SKINS.indices) {
            val sk = SKINS[i]
            val l = x0 + (i % 3) * (cw + gap)
            val t0 = 62f * u + (i / 3) * (ch + gap)
            rf.set(l, t0, l + cw, t0 + ch)
            p.style = Paint.Style.FILL; p.color = Color.argb(45, 255, 255, 255); cv.drawRoundRect(rf, 14f * u, 14f * u, p)
            if (i == skinSel) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f * u; p.color = col("#FFD54F"); cv.drawRoundRect(rf, 14f * u, 14f * u, p)
            }
            btns.add(Btn(B_SKIN + i, RectF(rf)))
            drawBurrito(l + cw / 2f, t0 + 90f * u, 0.42f * u, sk, anim * 8f, false, false)
            text(sk.name, l + cw / 2f, t0 + 110f * u, 15f * u, Color.WHITE)
            val label = when { i == skinSel -> "Selected"; owned.contains(i) -> "Owned"; else -> "${sk.price} coins" }
            text(label, l + cw / 2f, t0 + 128f * u, 13f * u, if (owned.contains(i)) col("#A5D6A7") else col("#FFE58A"))
        }
    }
}
