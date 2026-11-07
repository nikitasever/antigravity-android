package com.google.antigravity.ui.components

import android.Manifest
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Экран приветствия и входа 1:1 как в официальном ChatGPT Android 1.2026.293,
 * адаптированный в фирменном стиле Antigravity:
 * - Живая орбитальная 3D-анимация сферы Antigravity в центре
 * - Печатающийся текст с пульсирующей точкой-курсором «●» по строкам из ресурсов ChatGPT 1.2026.293
 *   (0x7f130f0e..0x7f130f16: «Давайте создавать●», «Давайте проектировать●», «Давайте изобретать●»...)
 * - Нижняя карточка авторизации («Продолжить с Google», «Продолжить сессию с ПК», «Войти»)
 */
@Composable
fun ChatGptStyleGoogleLoginScreen(
    pcInstanceName: String,
    isPcBridgeOnline: Boolean,
    onOpenGoogleOAuthWeb: () -> Unit,
    onConnectPcSessionNow: () -> Unit,
    onStartPhoneLocalAgent: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.google.antigravity.ui.design.DesignColors.Bg)
            .windowInsetsPadding(WindowInsets.systemBars),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Верхняя минималистичная панель в стиле ChatGPT 1.2026.293
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Antigravity",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Пропустить",
                color = Color(0xFF9E9E9E),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onStartPhoneLocalAgent)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        // Quiet welcome; no invented atom, orbit animation, or subscription claims.
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center) {
            Text("Чем займёмся?", color = Color.White, fontSize = 34.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                textAlign = TextAlign.Center)
        }

        Surface(
            color = Color(0xFF171717),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 0x7f130f0d: "Продолжить с Google"
                Button(
                    onClick = onConnectPcSessionNow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    GoogleFourColorMark(modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Продолжить с Google",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Color.Black
                    )
                }

                // Кнопка синхронизации с рабочей станцией ПК (AI Pro)
                Button(
                    onClick = onConnectPcSessionNow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2A2A2A),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        text = "Продолжить с ПК",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                }

                // Гостевой / автономный режим
                OutlinedButton(
                    onClick = onStartPhoneLocalAgent,
                    shape = RoundedCornerShape(26.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF383838)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF1A1A1A),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        text = "Продолжить без входа",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun GoogleFourColorMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.22f
        val pad = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(pad, pad)
        // Красный (верх)
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = -145f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Жёлтый (лево)
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 140f,
            sweepAngle = 75f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Зелёный (низ)
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 45f,
            sweepAngle = 95f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Синий (право + перекладина)
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -10f,
            sweepAngle = 55f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(size.width * 0.52f, size.height * 0.5f),
            end = Offset(size.width - pad * 0.4f, size.height * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Butt
        )
    }
}

/**
 * Полноэкранная веб-синхронизация (Раздел 5.4):
 * - Компактная шапка в одну строку: стрелка назад, заголовок, переключатель «ПК-сессия / antigravity.google.com», кнопка обновить.
 * - Никакого сырого URL на экране.
 * - `WebView` занимает весь оставшийся экран (`MATCH_PARENT`).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AntigravityGoogleWebConsole(
    pcInstanceName: String,
    pcBridgeUrl: String = "",
    activeConversationId: String = "",
    initialUrl: String = "https://antigravity.google.com/",
    onGoogleLoginDetected: (String) -> Unit,
    onSwitchToNativeChat: () -> Unit
) {
    val localEmulatorMirrorUrl = remember(activeConversationId) {
        "http://10.0.2.2:8787/web?session=$activeConversationId"
    }
    val liveMirrorUrl = remember(pcBridgeUrl, activeConversationId) {
        val base = pcBridgeUrl.trim().trimEnd('/')
        if (base.isNotEmpty()) "$base/web?session=$activeConversationId" else localEmulatorMirrorUrl
    }
    val primaryMirrorUrl = liveMirrorUrl

    var isLiveMirrorMode by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val mirrorContext = LocalContext.current
    val mirrorScope = rememberCoroutineScope()
    val pairingClient = remember(mirrorContext) { com.google.antigravity.network.BridgePairingClient(
        com.google.antigravity.network.BridgeCredentials(mirrorContext)) }
    var mirrorRequestGeneration by remember { mutableStateOf(0) }
    fun loadOwnerMirror(view: WebView?) {
        if (view == null) return
        val requestGeneration = ++mirrorRequestGeneration
        mirrorScope.launch {
            isLoading = true
            val address = try { pairingClient.browserUrl(primaryMirrorUrl, activeConversationId) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (requestGeneration != mirrorRequestGeneration || !isLiveMirrorMode) return@launch
            if (address != null) view.loadUrl(address)
            else view.loadDataWithBaseURL(null, "<html lang='ru'><body style='background:#151515;color:#eee;font-family:sans-serif;padding:24px'><h2>Подключение к ПК</h2><p>Выполните сопряжение в настройках приложения и проверьте соединение с PC-Bridge.</p></body></html>", "text/html", "UTF-8", null)
        }
    }

    var triedMirrorFallback by remember { mutableStateOf(false) }
    DisposableEffect(webViewRef) {
        val view = webViewRef
        onDispose { view?.stopLoading(); view?.destroy() }
    }
    LaunchedEffect(primaryMirrorUrl, webViewRef) {
        if (isLiveMirrorMode) loadOwnerMirror(webViewRef)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        // Компактная шапка без сырых URL
        Surface(
            color = Color(0xFF141414),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(0.95f)
                ) {
                    IconButton(
                        onClick = onSwitchToNativeChat,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад в чат",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = "Веб-синхр.",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Компактный переключатель «ПК-сессия / antigravity.google.com»
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF212121))
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isLiveMirrorMode) Color(0xFF2F2F2F) else Color.Transparent)
                            .clickable {
                                isLiveMirrorMode = true
                                triedMirrorFallback = false
                                loadOwnerMirror(webViewRef)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "ПК-сессия",
                            color = if (isLiveMirrorMode) Color.White else Color(0xFF9E9E9E),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (!isLiveMirrorMode) Color(0xFF2F2F2F) else Color.Transparent)
                            .clickable {
                                isLiveMirrorMode = false
                                webViewRef?.loadUrl(initialUrl)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "antigravity.google.com",
                            color = if (!isLiveMirrorMode) Color.White else Color(0xFF9E9E9E),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                IconButton(
                    onClick = { if (isLiveMirrorMode) loadOwnerMirror(webViewRef) else webViewRef?.reload() },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Обновить",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        // WebView занимает 100% оставшегося экрана (MATCH_PARENT)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(android.graphics.Color.BLACK)
                        webViewRef = this

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            mediaPlaybackRequiresUserGesture = false
                            allowFileAccess = false
                            allowContentAccess = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            userAgentString =
                                "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                if (request == null) return
                                val trustedOrigin = request.origin.host in listOf(
                                    android.net.Uri.parse(primaryMirrorUrl).host,
                                    android.net.Uri.parse(initialUrl).host
                                )
                                val allowed = request.resources.filter { resource ->
                                    trustedOrigin && resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE &&
                                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                }
                                if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                if (url != null) {
                                    CookieManager.getInstance().flush()
                                    if (url.contains("antigravity.google.com") && !url.contains("accounts.google.com")) {
                                        onGoogleLoginDetected("Аккаунт Google подключён")
                                    }
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true && isLiveMirrorMode && !triedMirrorFallback) {
                                    triedMirrorFallback = true
                                    isLoading = false
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                return false
                            }
                        }

                        // На реальном смартфоне сразу открывает публичный туннель, на эмуляторе — 10.0.2.2:8787
                        // The effect requests an owner-scoped, one-use browser ticket.
                    }
                },
                update = { view ->
                    if (view.layoutParams?.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                        view.layoutParams?.height != ViewGroup.LayoutParams.MATCH_PARENT
                    ) {
                        view.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                }
            )

            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp)
                )
            }
        }
    }
}
