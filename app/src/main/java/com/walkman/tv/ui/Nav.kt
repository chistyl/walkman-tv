package com.walkman.tv.ui

/** Top-nav sections, mirroring the RN TV app's NAV_MENUS. */
enum class NavSection(val label: String) {
    Recommend("推薦"),
    Leaderboard("排行榜"),
    Songlist("歌單"),
    Library("我的清單"),
    Search("搜尋"),
    Settings("設定"),
}
