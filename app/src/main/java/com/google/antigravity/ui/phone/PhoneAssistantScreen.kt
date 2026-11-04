package com.google.antigravity.ui.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.device.ParsedNaturalPhoneQuery
import com.google.antigravity.device.PhoneSearchCategory
import com.google.antigravity.device.PhoneSearchResultItem
import com.google.antigravity.device.PhoneStorageSummaryInfo
import com.google.antigravity.viewmodel.AntigravityViewModel
import com.google.antigravity.ui.design.DesignColors
import com.google.antigravity.ui.components.ReferencePageHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Экран «ИИ-поиск по телефону» (Раздел 5.5):
 * - Одно большое поле поиска «Спросите или найдите что угодно на телефоне».
 * - Чипсы фильтров: Все, Документы, Фото, Видео, Аудио, APK, Контакты, Приложения.
 * - Группированные карточки результатов с иконкой типа, размером, датой и миниатюрами фото (MediaStore).
 * - Нажатие открывает файл через FileProvider; долгое нажатие открывает меню:
 *   Открыть, Поделиться, Спросить ИИ об этом файле, Удалить.
 * - Блок «Недавние файлы», сводка по памяти и карточка «Доступ ко всем файлам».
 * - Фоновый поиск с debounce и разбором естественного языка («найди pdf про договор за прошлую неделю»).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhoneAssistantScreen(
    viewModel: AntigravityViewModel,
    onBack: () -> Unit,
    onAskInChat: (String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(PhoneSearchCategory.ALL) }
    var isSearching by remember { mutableStateOf(false) }
    var parsedQueryInfo by remember { mutableStateOf<ParsedNaturalPhoneQuery?>(null) }
    var searchResults by remember { mutableStateOf<List<PhoneSearchResultItem>>(emptyList()) }
    var recentFiles by remember { mutableStateOf<List<PhoneSearchResultItem>>(emptyList()) }
    var storageSummary by remember { mutableStateOf<PhoneStorageSummaryInfo?>(null) }
    var selectedItemForMenu by remember { mutableStateOf<PhoneSearchResultItem?>(null) }
    var statusToastText by remember { mutableStateOf("") }

    // Первичная загрузка сводки по памяти и недавних файлов в фоне
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val summary = viewModel.phoneController.getStorageSummaryInfo()
            val recent = viewModel.phoneController.loadRecentPhoneItems(limit = 20)
            storageSummary = summary
            recentFiles = recent
        }
    }

    // Поиск с debounce в фоне без блокировки UI
    LaunchedEffect(searchQuery, selectedCategory) {
        if (searchQuery.isBlank() && selectedCategory == PhoneSearchCategory.ALL) {
            parsedQueryInfo = null
            searchResults = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        delay(240L)
        val (parsed, found) = viewModel.phoneController.performStructuredPhoneSearch(
            rawQuery = searchQuery,
            selectedCategory = selectedCategory
        )
        parsedQueryInfo = parsed
        searchResults = found
        isSearching = false
    }

    val activeItems = if (searchQuery.isBlank() && selectedCategory == PhoneSearchCategory.ALL) {
        recentFiles
    } else {
        searchResults
    }

    val groupedItems = remember(activeItems) {
        activeItems
            .groupBy { it.category }
            .entries
            .sortedBy {
                when (it.key) {
                    PhoneSearchCategory.DOCUMENTS -> 0
                    PhoneSearchCategory.APK -> 1
                    PhoneSearchCategory.PHOTOS -> 2
                    PhoneSearchCategory.VIDEOS -> 3
                    PhoneSearchCategory.AUDIO -> 4
                    PhoneSearchCategory.CONTACTS -> 5
                    PhoneSearchCategory.APPS -> 6
                    PhoneSearchCategory.ALL -> 7
                }
            }
            .associate { it.key to it.value }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DesignColors.Bg)
    ) {
        ReferencePageHeader("Файлы телефона", navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        }, actions = {
            IconButton(onClick = {
                val (visiblePrompt, hiddenCtx) = buildAiContextPrompt(searchQuery, activeItems, viewModel.phoneController)
                viewModel.sendUserPrompt(rawPrompt = visiblePrompt, viaVoice = false, hiddenPhoneContext = hiddenCtx)
            }) { Icon(Icons.Default.AutoAwesome, "Спросить ИИ о найденных файлах") }
        })

        // Большое поле поиска «Спросите или найдите что угодно на телефоне»
        Surface(
            color = DesignColors.Surface,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .border(1.dp, DesignColors.SurfaceHigh, RoundedCornerShape(26.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = DesignColors.TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Спросите или найдите что угодно на телефоне",
                            color = DesignColors.TextSubtle,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(Color.White),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            coroutineScope.launch {
                                isSearching = true
                                val (parsed, found) = viewModel.phoneController.performStructuredPhoneSearch(
                                    rawQuery = searchQuery,
                                    selectedCategory = selectedCategory
                                )
                                parsedQueryInfo = parsed
                                searchResults = found
                                isSearching = false
                            }
                        }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Очистить",
                            tint = DesignColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            viewModel.voiceEngine.startInlineDictation { dictated ->
                                searchQuery = dictated
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Голосовой ввод",
                            tint = Color(0xFFB4B4B4),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Горизонтальные чипсы фильтров: Все, Документы, Фото, Видео, Аудио, APK, Контакты, Приложения
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PhoneSearchCategory.entries.forEach { category ->
                val isSelected = selectedCategory == category
                Surface(
                    color = if (isSelected) Color.White else DesignColors.Surface,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { selectedCategory = category }
                ) {
                    Text(
                        text = category.labelRu,
                        color = if (isSelected) Color.Black else Color(0xFFE3E3E3),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // Индикатор фонового поиска
        AnimatedVisibility(visible = isSearching) {
            LinearProgressIndicator(
                color = DesignColors.AccentBlue,
                trackColor = DesignColors.Surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(2.dp)
            )
        }

        // Подсказка распознанного естественного запроса
        val humanFilter = parsedQueryInfo?.humanFilterSummaryRu.orEmpty()
        if (searchQuery.isNotBlank() && humanFilter.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = DesignColors.AccentBlue,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Распознан фильтр: $humanFilter",
                    color = DesignColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (statusToastText.isNotBlank()) {
            Surface(
                color = DesignColors.SurfaceHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(
                    text = statusToastText,
                    color = DesignColors.TextPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // Основной список: карточка доступа, сводка по памяти, быстрые примеры и группированные результаты
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Карточка получения полного доступа к файлам (если ещё не выдан)
            val summary = storageSummary
            if (summary != null && !summary.hasAllFilesAccess) {
                item(key = "all_files_onboarding") {
                    Surface(
                        color = DesignColors.Surface,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(18.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = DesignColors.AccentBlue,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Доступ ко всем файлам",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Разрешите доступ к памяти телефона, чтобы ассистент находил документы PDF, архивы и APK во всех папках устройства.",
                                color = Color(0xFFB4B4B4),
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    try {
                                        context.startActivity(viewModel.phoneController.buildAllFilesAccessIntent())
                                    } catch (_: Throwable) {
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(
                                    text = "Открыть настройки доступа",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Краткая сводка по памяти телефона (показывается на стартовом экране поиска)
            if (searchQuery.isBlank() && selectedCategory == PhoneSearchCategory.ALL && summary != null) {
                item(key = "storage_summary") {
                    StorageSummaryCard(
                        summary = summary,
                        onQuickQueryClick = { q -> searchQuery = q }
                    )
                }
            }

            // Заголовок секции
            item(key = "results_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (searchQuery.isBlank() && selectedCategory == PhoneSearchCategory.ALL) {
                            "Недавние файлы"
                        } else {
                            "Найдено: ${activeItems.size}"
                        },
                        color = Color(0xFFE3E3E3),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (searchQuery.isNotBlank() && activeItems.isNotEmpty()) {
                        Text(
                            text = "Спросить ИИ о результатах →",
                            color = DesignColors.AccentBlue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                val (cleanPrompt, hiddenCtx) = buildAiContextPrompt(
                                    searchQuery,
                                    activeItems,
                                    viewModel.phoneController
                                )
                                viewModel.sendUserPrompt(
                                    rawPrompt = cleanPrompt,
                                    viaVoice = false,
                                    hiddenPhoneContext = hiddenCtx
                                )
                            }
                        )
                    }
                }
            }

            if (activeItems.isEmpty() && !isSearching) {
                item(key = "empty_state") {
                    Surface(
                        color = Color(0xFF141414),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Ничего не найдено на телефоне",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Попробуйте изменить запрос или поручите ИИ создать нужный файл прямо в чате.",
                                color = DesignColors.TextSubtle,
                                fontSize = 13.sp
                            )
                            if (searchQuery.isNotBlank()) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = { onAskInChat(searchQuery) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = DesignColors.Surface,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = DesignColors.AccentBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Передать запрос ИИ в чат", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            } else {
                groupedItems.forEach { (category, groupList) ->
                    item(key = "group_title_${category.name}") {
                        Text(
                            text = "${category.labelRu} (${groupList.size})",
                            color = DesignColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                        )
                    }

                    itemsIndexed(
                        items = groupList,
                        key = { idx, it -> "${category.name}_${idx}_${it.id}" }
                    ) { _, item ->
                        PhoneResultItemCard(
                            item = item,
                            onClick = {
                                val opened = viewModel.phoneController.openSearchResultItem(item)
                                if (!opened) {
                                    selectedItemForMenu = item
                                }
                            },
                            onLongClick = {
                                selectedItemForMenu = item
                            }
                        )
                    }
                }
            }
        }
    }

    // Контекстное меню по долгому нажатию (Открыть, Поделиться, Спросить ИИ об этом файле, Удалить)
    val menuTarget = selectedItemForMenu
    if (menuTarget != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedItemForMenu = null },
            sheetState = sheetState,
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = menuTarget.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${menuTarget.category.labelRu} • ${menuTarget.sizeFormatted} • ${menuTarget.dateFormatted}",
                    color = DesignColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
                )

                HorizontalDivider(color = Color(0xFF2E2E2E))

                // 1. Открыть
                ContextMenuActionRow(
                    icon = Icons.Default.OpenInNew,
                    title = "Открыть",
                    tint = Color.White,
                    onClick = {
                        selectedItemForMenu = null
                        val ok = viewModel.phoneController.openSearchResultItem(menuTarget)
                        if (!ok) {
                            statusToastText = "Не найдено системное приложение для открытия «${menuTarget.title}»"
                        }
                    }
                )

                // 2. Поделиться
                ContextMenuActionRow(
                    icon = Icons.Default.Share,
                    title = "Поделиться",
                    tint = Color.White,
                    onClick = {
                        selectedItemForMenu = null
                        viewModel.phoneController.shareSearchResultItem(menuTarget)
                    }
                )

                // 3. Спросить ИИ об этом файле
                ContextMenuActionRow(
                    icon = Icons.Default.AutoAwesome,
                    title = "Спросить ИИ об этом файле",
                    tint = DesignColors.AccentBlue,
                    onClick = {
                        val target = menuTarget
                        selectedItemForMenu = null
                        val preview = if (target.absolutePath.isNotBlank()) {
                            viewModel.phoneController.extractCleanReadableText(File(target.absolutePath), 1500)
                        } else ""
                        val visiblePrompt = "Проанализируй файл «${target.title}» (${target.sizeFormatted})"
                        val hiddenFileContext = buildString {
                            append("Выбранный файл на телефоне: ${target.title}\n")
                            append("Категория: ${target.category.labelRu}, размер: ${target.sizeFormatted}, дата: ${target.dateFormatted}\n")
                            if (target.absolutePath.isNotBlank()) {
                                append("Путь: ${target.absolutePath}\n")
                            }
                            if (preview.isNotBlank()) {
                                append("Содержимое файла:\n$preview")
                            }
                        }
                        viewModel.sendUserPrompt(
                            rawPrompt = visiblePrompt,
                            viaVoice = false,
                            hiddenPhoneContext = hiddenFileContext
                        )
                    }
                )

                // 4. Удалить
                if (menuTarget.absolutePath.isNotBlank()) {
                    ContextMenuActionRow(
                        icon = Icons.Default.DeleteOutline,
                        title = "Удалить",
                        tint = Color(0xFFEF4444),
                        onClick = {
                            val target = menuTarget
                            selectedItemForMenu = null
                            coroutineScope.launch(Dispatchers.IO) {
                                val res = viewModel.phoneController.deleteFile(target.absolutePath)
                                val updatedRecent = viewModel.phoneController.loadRecentPhoneItems(limit = 20)
                                withContext(Dispatchers.Main) {
                                    statusToastText = res.summaryRu
                                    recentFiles = updatedRecent
                                    searchResults = searchResults.filterNot { it.id == target.id }
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun StorageSummaryCard(
    summary: PhoneStorageSummaryInfo,
    onQuickQueryClick: (String) -> Unit
) {
    Surface(
        color = DesignColors.Surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF282828), RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = DesignColors.AccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Память телефона",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = String.format(
                        Locale.US,
                        "Свободно %.1f ГБ из %.1f ГБ",
                        summary.freeStorageGb,
                        summary.totalStorageGb
                    ),
                    color = Color(0xFFB4B4B4),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val usedFraction = (summary.usedStorageGb / summary.totalStorageGb).coerceIn(0.05f, 0.98f)
            LinearProgressIndicator(
                progress = { usedFraction },
                color = DesignColors.AccentBlue,
                trackColor = Color(0xFF2A2A2A),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Быстрые поисковые подсказки на естественном языке
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val quickPrompts = listOf(
                    "найди pdf про договор за прошлую неделю",
                    "заметки по проекту",
                    "установленные приложения"
                )
                quickPrompts.forEach { sample ->
                    Surface(
                        color = Color(0xFF242424),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.clickable { onQuickQueryClick(sample) }
                    ) {
                        Text(
                            text = sample,
                            color = Color(0xFFD4D4D4),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneResultItemCard(
    item: PhoneSearchResultItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        color = DesignColors.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.category == PhoneSearchCategory.PHOTOS) {
                PhotoThumbnailBox(item = item)
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(categoryBadgeColor(item.category).copy(alpha = 0.16f))
                ) {
                    Icon(
                        imageVector = categoryIcon(item.category),
                        contentDescription = null,
                        tint = categoryBadgeColor(item.category),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${item.sizeFormatted} • ${item.dateFormatted}",
                    color = DesignColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.snippet.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "«${item.snippet}»",
                        color = DesignColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoThumbnailBox(item: PhoneSearchResultItem) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.id) {
        value = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && item.contentUriString.startsWith("content://")) {
                    context.contentResolver.loadThumbnail(
                        Uri.parse(item.contentUriString),
                        Size(128, 128),
                        null
                    )
                } else if (item.absolutePath.isNotBlank()) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(item.absolutePath, opts)
                } else null
            } catch (_: Throwable) {
                null
            }
        }
    }

    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF10B981).copy(alpha = 0.16f))
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = Color(0xFF10B981),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun ContextMenuActionRow(
    icon: ImageVector,
    title: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            color = tint,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun categoryIcon(cat: PhoneSearchCategory): ImageVector {
    return when (cat) {
        PhoneSearchCategory.ALL -> Icons.Default.Description
        PhoneSearchCategory.DOCUMENTS -> Icons.Default.Description
        PhoneSearchCategory.PHOTOS -> Icons.Default.Image
        PhoneSearchCategory.VIDEOS -> Icons.Default.VideoFile
        PhoneSearchCategory.AUDIO -> Icons.Default.AudioFile
        PhoneSearchCategory.APK -> Icons.Default.Android
        PhoneSearchCategory.CONTACTS -> Icons.Default.ContactPhone
        PhoneSearchCategory.APPS -> Icons.Default.Apps
    }
}

private fun categoryBadgeColor(cat: PhoneSearchCategory): Color {
    return when (cat) {
        PhoneSearchCategory.ALL -> DesignColors.AccentBlue
        PhoneSearchCategory.DOCUMENTS -> DesignColors.AccentBlue
        PhoneSearchCategory.PHOTOS -> Color(0xFF10B981)
        PhoneSearchCategory.VIDEOS -> Color(0xFFF59E0B)
        PhoneSearchCategory.AUDIO -> Color(0xFFA855F7)
        PhoneSearchCategory.APK -> Color(0xFF34D399)
        PhoneSearchCategory.CONTACTS -> Color(0xFF38BDF8)
        PhoneSearchCategory.APPS -> Color(0xFF818CF8)
    }
}

private fun buildAiContextPrompt(
    rawQuery: String,
    items: List<PhoneSearchResultItem>,
    phoneController: com.google.antigravity.device.PhoneResourceController
): Pair<String, String> {
    val cleanQuery = rawQuery.trim().ifEmpty { "Покажи сводку по моим недавним файлам на телефоне" }
    val topItems = items.take(8)
    if (topItems.isEmpty()) {
        return cleanQuery to "Запрос отправлен из экрана «Поиск по телефону»: $cleanQuery"
    }

    var previewsExtracted = 0
    val contextLines = topItems.joinToString("\n") { item ->
        val cleanFolder = phoneController.humanizePhoneFolderRu(item.absolutePath, item.category)
        val previewText = if (item.snippet.isNotBlank()) {
            item.snippet
        } else if (previewsExtracted < 4 && item.absolutePath.isNotBlank()) {
            val extracted = phoneController.extractCleanReadableText(File(item.absolutePath), 650).trim()
            if (extracted.isNotBlank()) {
                previewsExtracted++
                extracted.replace(Regex("\\s+"), " ").take(450)
            } else ""
        } else ""
        val extra = if (previewText.isNotBlank()) " — фрагмент содержимого: «$previewText»" else ""
        "• **${item.title}** (${item.category.labelRu}, ${item.sizeFormatted}, ${item.dateFormatted}, раздел: $cleanFolder)$extra"
    }
    return cleanQuery to "Найденные на телефоне элементы:\n$contextLines"
}


