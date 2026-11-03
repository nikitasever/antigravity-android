package com.google.antigravity.ui.design

import androidx.compose.ui.graphics.Color

// Дизайн-токены «как у них» — сверка с референсами (3 ноября 2026,
// доказательства — декомпиляция/ресурсы APK, не ощущения):
//
// ChatGPT Android 1.2026.293: цвета декодированы из декомпиляции
// (вызовы `qdm0.h(<decimal ARGB>)` = конструктор Compose Color):
//   нейтрали тёмной темы #0D0D0D (фон), #212121 / #303030 / #414141
//   (поверхности/границы), #F3F3F3 (основной текст), #C4C3CB / #89888D
//   (вторичный/приглушённый); акценты #3566F0 (синий), #04BB9F (бирюза),
//   #53B559 / #3A843F (зелёный), #FFCC38 (жёлтый), #FF8066 (коралл),
//   #FA70AB (розовый), #A25BFF / #C8A4FB / #DCC7FE (фиолетовый),
//   #00B1FF (голубой), #B6D80B (лайм), #DD6ADC (маджента).
//   Шрифты: OpenAI Sans (openai_sans_variable.ttf, openai_sans_medium.ttf) и
//   Söhne (soehne_circle.otf, soehne_kraeftig.ttf) из
//   assets/composeResources/com.openai.kmp.platform.design.resources/font.
//
// Claude Android 1.261029.20: res/values/colors.xml (имена обфусцированы,
// значения реальны): тёплая бумага #FAF9F5 / #F3F3F0 / #F0EFEC / #E7E6E1,
// коралл #D97757 / #C6613F, шалфей #7FA87F, тёмное #131313 / #141413 /
// #212121 / #303030; шрифты Google Sans (res/font/google_sans_*_compat.xml).
//
// Честная граница: шрифты референсов (Google Sans, OpenAI Sans, Söhne) не
// перераспространяются и НЕ включены в комплект — типографика приближена
// кеглями и весами системного sans-serif-medium (факт шрифтов зафиксирован
// здесь и в EXTENSIONS_COMPATIBILITY.md §22, «требует проверки» визуального
// подобия на глаз). Светлая тема ChatGPT (#FFFFFF/#F3F3F3-семейство) и
// тёплая светлая тема Claude (#FAF9F5) существуют в референсах; наша
// тёмная база сохранена как базовая тема Antigravity.

/** Цветовые токены поверхности (тёмная база по нейтралям ChatGPT). */
object DesignColors {
    // Нейрали ChatGPT (декодированы из декомпиляции — см. шапку файла).
    val Bg = Color(0xFF0D0D0D)
    val Surface = Color(0xFF212121)
    val SurfaceHigh = Color(0xFF303030)
    val Border = Color(0xFF414141)
    val TextPrimary = Color(0xFFF3F3F3)
    val TextSecondary = Color(0xFFC4C3CB)
    val TextSubtle = Color(0xFF89888D)

    // Акценты ChatGPT.
    val AccentBlue = Color(0xFF3566F0)
    val AccentTeal = Color(0xFF04BB9F)
    val AccentGreen = Color(0xFF53B559)
    val AccentAmber = Color(0xFFFFCC38)
    val AccentCoral = Color(0xFFFF8066)
    val AccentRed = Color(0xFFDD6ADC) // их розово-маджентовый для ошибок-акцентов

    // Тёплая палитра Claude (для поверхностей в их стиле; факт из colors.xml).
    val ClaudePaper = Color(0xFFFAF9F5)
    val ClaudePaperDeep = Color(0xFFE7E6E1)
    val ClaudeCoral = Color(0xFFD97757)
    val ClaudeCoralDeep = Color(0xFFC6613F)
    val ClaudeSage = Color(0xFF7FA87F)
    val ClaudeInk = Color(0xFF131313)
}

/**
 * Кегли типографики: приведены к ритму мобильных поверхностей референсов
 * (заголовки листов ~18sp SemiBold, заголовки секций ~14sp SemiBold, основной
 * текст ~14sp, подписи ~12sp, микро-подписи ~11sp). Шрифты референсов не
 * входят в комплект (см. шапку файла) — используются веса системного шрифта.
 */
object DesignType {
    const val TITLE_SP = 18f
    const val SECTION_SP = 14f
    const val BODY_SP = 14f
    const val CAPTION_SP = 12f
    const val MICRO_SP = 11f
}
