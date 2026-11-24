# Setup Guide (Hinglish)

## 1. Build aur test
1. PC par Android Studio (latest) install karo.
2. File > Open > ElBurritoRunner folder select karo, Gradle sync hone do (2-5 min).
3. Phone ya emulator par Run ▶. Game **landscape** me khulega. Test ads dikhne chahiye.
4. Icon: app folder par right click > New > Image Asset (PLAY_STORE_LISTING.md ke prompt se icon banao).

## 2. Controls
- Tap = jump, dobara tap = double jump
- Neeche swipe = slide (birds ke neeche se nikalne ke liye) / air me fast fall
- Back button = pause

## 3. AdMob (kamai yaha se)
1. admob.google.com par app add karo (Android).
2. 3 ad units banao: **Banner**, **Interstitial**, **Rewarded**.
3. Real IDs replace karo:
   - AndroidManifest.xml: APPLICATION_ID
   - MainActivity.kt: object Ads (BANNER, INTERSTITIAL, REWARDED)
4. Testing me hamesha TEST IDs rakho. Apne ads par khud click mat karna.

## 4. Game tuning (GameView.kt me)
- Speed ramp: `250f + t * 5.5f`, max `640f` (updatePlay)
- Interstitial frequency: `games % 3 == 0` (maybeInterstitial)
- Skins/prices: `SKINS` list
- Jump power: `-720f * u` aur `-640f * u`
- Daily bonus: init block (50 coins)

## 5. Release
1. Build > Generate Signed App Bundle (.aab). Keystore ka backup safe rakho.
2. Play Console (one-time $25): Create app > Game > Free.
3. Listing text aur graphics PLAY_STORE_LISTING.md se. Privacy policy host karke link do.
4. Data safety: "Device or other IDs (Advertising ID) used for advertising" declare karo.
5. Ads declaration: Yes. Content rating questionnaire bharo (violence-free arcade).
6. New personal developer accounts ke liye closed testing rules Play Console me current check karke complete karo.
7. targetSdk Play ki current requirement ke hisaab se check kar lena.

## 6. Growth tips
- Gameplay clips + PLAY_STORE_LISTING.md ka Veo promo prompt TikTok par daalo, bio me Play Store link.
- Reviews ke baad naye skins/power-ups update me daalo, retention badhta hai.
