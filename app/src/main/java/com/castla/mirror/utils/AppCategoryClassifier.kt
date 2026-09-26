package com.castla.mirror.utils

object AppCategoryClassifier {
    /** Messengers / social apps that often declare no category. */
    private val socialPkgs = setOf(
        "com.whatsapp", "com.viber.voip", "org.telegram.messenger", "org.thunderdog.challegram",
        "com.facebook.orca", "com.facebook.katana", "com.instagram.android", "com.snapchat.android",
        "com.discord", "com.zhiliaoapp.musically", "com.twitter.android", "com.kakao.talk",
        "jp.naver.line.android", "com.skype.raider", "com.microsoft.teams", "org.thoughtcrime.securesms"
    )

    /**
     * [androidCategory] is ApplicationInfo.category (-1 = undefined): used when no
     * package/label keyword matches, so the "Apps" bucket is not one huge list.
     */
    fun classify(pkg: String, label: String, androidCategory: Int = -1): String {
        val p = pkg.lowercase()
        val l = label.lowercase()
        
        val navPkgs = setOf("com.skt.tmap", "com.skt.skaf.l001mtm091", "com.locnall.kimgisa", "com.kakao.taxi", "com.kakaonavi", "com.nhn.android.nmap", "com.nhn.android.navermap", "com.google.android.apps.maps", "com.waze", "net.daum.android.map", "com.thinkware.inavic", "com.mnav.atlan", "com.mappy.app", "com.here.app.maps", "com.mapbox.mapboxandroiddemo")
        val videoPkgs = setOf("com.google.android.youtube", "com.netflix.mediaclient", "com.disney.disneyplus", "com.disney.disneyplus.kr", "com.wavve.player", "net.cj.cjhv.gs.tving", "com.coupang.play", "com.amazon.avod.thirdpartyclient", "com.amazon.avod", "tv.twitch.android.app", "com.frograms.watcha", "kr.co.captv.pooq", "com.hbo.hbomax", "com.apple.atve.androidtv.appletv", "com.bbc.iplayer.android", "com.sbs.vod.sbsnow", "com.kbs.kbsn", "com.imbc.mbcvod", "com.vikinc.vikinchannel", "kr.co.nowcom.mobile.aladdin", "com.dmp.hoyatv")
        val musicPkgs = setOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.iloen.melon", "com.kt.android.genie", "com.sktelecom.flomusic", "com.naver.vibe", "com.soribada.android", "com.soundcloud.android", "com.pandora.android", "com.amazon.mp3", "com.apple.android.music", "com.shazam.android", "fm.castbox.audiobook.radio.podcast", "com.samsung.android.app.podcast", "com.google.android.apps.podcasts")
        
        if (navPkgs.any { p.startsWith(it) } || p.contains("map") || p.contains("navi") || p.contains("waze") || l.contains("지도") || l.contains("내비")) return "NAVIGATION"
        if (videoPkgs.any { p.startsWith(it) } || p.contains("video") || p.contains("movie") || p.contains("ott") || p.contains("tv") || l.contains("동영상") || l.contains("영화")) return "VIDEO"
        if (musicPkgs.any { p.startsWith(it) } || p.contains("music") || p.contains("audio") || p.contains("radio") || l.contains("음악") || l.contains("라디오")) return "MUSIC"
        
        if (socialPkgs.any { p.startsWith(it) }) return "SOCIAL"

        return when (androidCategory) {
            0 -> "GAMES"        // CATEGORY_GAME
            1 -> "MUSIC"        // CATEGORY_AUDIO
            2 -> "VIDEO"        // CATEGORY_VIDEO
            4 -> "SOCIAL"       // CATEGORY_SOCIAL
            6 -> "NAVIGATION"   // CATEGORY_MAPS
            else -> "OTHER"
        }
    }
}
