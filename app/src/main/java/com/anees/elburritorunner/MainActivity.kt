package com.anees.elburritorunner

import android.app.Activity
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

object Ads {
    // TEST IDs (Google ke official). Release se pehle apne AdMob IDs se replace karo.
    const val BANNER = "ca-app-pub-3940256099942544/6300978111"
    const val INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    const val REWARDED = "ca-app-pub-3940256099942544/5224354917"
}

@Suppress("DEPRECATION")
class MainActivity : Activity(), Host {

    private lateinit var game: GameView
    private var interstitial: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var tone: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this) {}
        try { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (e: RuntimeException) { tone = null }

        game = GameView(this, this)
        val banner = AdView(this).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = Ads.BANNER
            loadAd(AdRequest.Builder().build())
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(game, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(banner, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop,
                    ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            ins
        }
        setContentView(root)
        loadInterstitial()
        loadRewarded()
    }

    override fun onResume() { super.onResume(); game.start() }
    override fun onPause() { game.pauseIfPlaying(); game.stop(); super.onPause() }
    override fun onDestroy() { tone?.release(); super.onDestroy() }
    override fun onBackPressed() { if (!game.onBack()) super.onBackPressed() }

    // ───── Host ─────
    override fun sfx(type: Int) {
        val t = tone ?: return
        when (type) {
            0 -> t.startTone(ToneGenerator.TONE_PROP_BEEP, 40)       // jump
            1 -> t.startTone(ToneGenerator.TONE_PROP_ACK, 60)        // coin / power-up
            2 -> t.startTone(ToneGenerator.TONE_PROP_NACK, 220)      // crash
            else -> t.startTone(ToneGenerator.TONE_PROP_PROMPT, 120) // shield pop
        }
    }

    override fun haptic() {
        val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        else v.vibrate(60)
    }

    override fun rewardedReady(): Boolean = rewarded != null

    override fun showRewarded(onReward: () -> Unit) {
        val ad = rewarded ?: return
        rewarded = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { loadRewarded() }
        }
        ad.show(this, OnUserEarnedRewardListener { onReward() })
    }

    override fun showInterstitial() {
        val ad = interstitial ?: return
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { loadInterstitial() }
        }
        ad.show(this)
    }

    private fun loadInterstitial() {
        InterstitialAd.load(this, Ads.INTERSTITIAL, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { interstitial = null }
            })
    }

    private fun loadRewarded() {
        RewardedAd.load(this, Ads.REWARDED, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) { rewarded = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { rewarded = null }
            })
    }
}
