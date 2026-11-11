@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lexicon.domain.*
import dev.lexicon.domain.Card as LearningCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun StatusLine(message: String, onDismiss: (() -> Unit)? = null) {
    val isError = message.startsWith("[error]")
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
        .animateContentSize(tween(180)).semantics { liveRegion = LiveRegionMode.Polite }
        .padding(horizontal = 22.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(5.dp).background(if (isError) color else MaterialTheme.colorScheme.primary, CircleShape))
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onDismiss != null) TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("关闭", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
internal fun EmptyPanel(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) { Spacer(Modifier.height(24.dp)); Button(onClick = onAction, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 13.dp)) { Text(action) } }
    }
}

@Composable
internal fun ArticleExplorer(
    articles: List<Article>, sources: List<Source>, busy: Boolean, onSync: () -> Unit,
    onOpen: (Article) -> Unit, onSources: () -> Unit, onBaseline: ((Article) -> Unit)? = null,
) {
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var topicsOpen by rememberSaveable { mutableStateOf(false) }
    val shown = remember(articles, topic, sourceId) {
        articles.filter { (topic == null || it.topic == topic) && (sourceId == null || sourceId in it.sourceIds) }
    }
    val availableSources = remember(articles, sources) { sources.filter { it.enabled || articles.any { a -> it.id in a.sourceIds } } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { topicsOpen = !topicsOpen }, contentPadding = PaddingValues(horizontal = 2.dp)) {
                Text("${if (topicsOpen) "▾" else "▸"} ${topic?.let(::sourceTopicLabel) ?: "全部主题"}")
            }
            TextButton(onClick = onSync, enabled = !busy) { Text(if (busy) "同步中…" else "↓ 同步", style = MaterialTheme.typography.labelLarge) }
        }
        AnimatedVisibility(topicsOpen, enter = expandVertically(tween(220)) + fadeIn(tween(160)), exit = shrinkVertically(tween(180)) + fadeOut(tween(120))) {
            FlowRow(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(topic == null, { topic = null; topicsOpen = false }, label = { Text("全部") })
                Topics.children.keys.forEach { name -> FilterChip(topic == name, { topic = name; topicsOpen = false }, label = { Text(sourceTopicLabel(name)) }) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourcePill("所有来源", sourceId == null) { sourceId = null }
            availableSources.forEach { source ->
                SourcePill(source.name, sourceId == source.id) { sourceId = if (sourceId == source.id) null else source.id }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 17.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${shown.size} 篇文章", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${shown.count { !it.read }} 未读", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shown.isEmpty()) EmptyPanel(
            if (articles.isEmpty()) "还没有文章" else "暂无匹配文章",
            if (articles.isEmpty()) "同步已订阅的来源，开始阅读。" else "试试其他主题或来源。",
            if (articles.isEmpty()) "同步文章" else "清除筛选",
        ) { if (articles.isEmpty()) onSync() else { topic = null; sourceId = null } }
        else LazyColumn(Modifier.fillMaxSize().testTag("article_history_list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shown, key = { it.id }) { article ->
                Surface(onClick = { onOpen(article) }, modifier = Modifier.fillMaxWidth().animateItem(), shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (article.captured) 0.85f else 0.5f))) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (article.captured) "│" else if (article.read) "●" else "○", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            Text(article.sourceName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            article.publishedAt?.let { Text(dateLabel(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text(article.title, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = ReadingSerif, fontSize = 23.sp, lineHeight = 29.sp),
                            maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (article.summary.isNotBlank()) Text(article.summary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                (article.subtopic ?: article.topic?.let(::sourceTopicLabel))?.let { InlineTag(it) }
                                article.wordCount?.let { InlineTag("${kotlin.math.ceil(it / 200.0).toInt().coerceAtLeast(1)} 分钟") }
                            }
                            if (onBaseline != null) TextButton(onClick = { onBaseline(article) },
                                modifier = Modifier.testTag("article_baseline_${article.id}")) { Text("自测") }
                            Text("→", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item { TextButton(onClick = onSources, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("管理来源") } }
        }
    }
}

@Composable private fun SourcePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 12.dp), style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun InlineTag(label: String) {
    Text(label, Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private enum class CardListFilter { ALL, DUE, PAUSED }

@Composable
internal fun CardLibrary(cards: List<LearningCard>, onOpen: (LearningCard) -> Unit, onExport: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(CardListFilter.ALL) }
    if (cards.isEmpty()) { EmptyPanel("还没有生词卡", "从文章中捕获单词即可创建卡片。"); return }
    val filtered = remember(cards, query, filter, now) { cards.filter { card ->
        card.content.headword.contains(query, true) && when (filter) {
            CardListFilter.ALL -> true
            CardListFilter.DUE -> card.isDueForReview(now)
            CardListFilter.PAUSED -> card.reviewPaused
        }
    }.sortedByDescending { it.createdAt } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f).testTag("card_library_search"), singleLine = true, placeholder = { Text("搜索词条…") },
                leadingIcon = { Text("/", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }, shape = RoundedCornerShape(12.dp))
            TextButton(onClick = onExport) { Text("导出") }
        }
        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(CardListFilter.ALL to "全部", CardListFilter.DUE to "待复习", CardListFilter.PAUSED to "已暂停").forEach { (value, label) ->
                FilterChip(filter == value, { filter = value }, label = { Text(label) }, modifier = Modifier.testTag("card_filter_${value.name.lowercase()}"))
            }
        }
        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f)) { EmptyPanel("没有匹配的卡片", "可以调整搜索词或筛选。", "清除筛选") { query = ""; filter = CardListFilter.ALL } }
        }
        if (filtered.isNotEmpty()) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.id }) { card ->
                OutlinedCard(onClick = { onOpen(card) }, modifier = Modifier.fillMaxWidth().animateItem().testTag("card_library_${card.id}"),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))) {
                    Column(Modifier.padding(18.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(card.content.headword, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            InlineTag(cardStateLabel(card))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(card.content.contextDefinition, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            InlineTag(examRelevanceLabel(card.content.examRelevance))
                            InlineTag(registerLabel(card.content.registerTag))
                            if (card.inaccurate) InlineTag("待核对")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CardDetail(card: LearningCard, busy: Boolean, onSave: (LearningCard, () -> Unit) -> Unit, onBack: () -> Unit,
    onSetReviewPaused: ((Boolean) -> Unit)? = null,
    backLabel: String = "← 所有卡片", onOpenSource: (() -> Unit)? = null, sourceActionLabel: String = "回原文",
) {
    var editing by rememberSaveable(card.id) { mutableStateOf(false) }
    var headword by rememberSaveable(card.id, card.updatedAt) { mutableStateOf(card.content.headword) }
    var definition by rememberSaveable(card.id, card.updatedAt) { mutableStateOf(card.content.contextDefinition) }
    var paraphrase by rememberSaveable(card.id, card.updatedAt) { mutableStateOf(card.content.paraphrase) }
    var phrases by rememberSaveable(card.id, card.updatedAt) { mutableStateOf(card.content.collocations.map { it.phrase }) }
    var examples by rememberSaveable(card.id, card.updatedAt) { mutableStateOf(card.content.collocations.map { it.example }) }
    var register by rememberSaveable(card.id, card.updatedAt) { mutableStateOf(card.content.registerTag) }
    var relevance by rememberSaveable(card.id, card.updatedAt) { mutableStateOf(card.content.examRelevance) }
    Column(Modifier.fillMaxSize().testTag("card_detail_${card.id}").verticalScroll(rememberScrollState()).padding(20.dp).animateContentSize(tween(220)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("card_back"), contentPadding = PaddingValues(0.dp)) { Text(backLabel) }
        Text(card.content.headword, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InlineTag(cardStateLabel(card)); InlineTag("复习 ${card.state.reps} 次")
            onSetReviewPaused?.let { change ->
                TextButton(enabled = !busy, onClick = { change(!card.reviewPaused) }, modifier = Modifier.testTag("card_review_pause")) {
                    Text(if (card.reviewPaused) "恢复复习" else "暂停复习")
                }
            }
        }
        if (card.reviewPaused) Text("保留卡片与复习进度；恢复后沿用原到期时间。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("card_review_pause_note"))
        FieldLabel(when (card.contextOrigin) { ContextOrigin.USER_SUPPLIED -> "用户补充语境"; ContextOrigin.AI_GENERATED -> "AI 改写语境"; else -> "原文语境" })
        QuoteBlock(card.sourceSentence)
        Text(card.sourceRef.display(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        onOpenSource?.let { open ->
            TextButton(enabled = !busy, onClick = open, modifier = Modifier.testTag("card_open_source"), contentPadding = PaddingValues(0.dp)) {
                Text(sourceActionLabel)
            }
        }
        if (editing) {
            OutlinedTextField(headword, { headword = it.take(120) }, Modifier.fillMaxWidth().testTag("card_headword_edit"), label = { Text("词条") }, singleLine = true, enabled = !busy)
            OutlinedTextField(definition, { definition = it.take(1_200) }, Modifier.fillMaxWidth().testTag("card_definition_edit"), label = { Text("语境英文释义") }, minLines = 3, enabled = !busy)
            phrases.indices.forEach { index ->
                OutlinedTextField(phrases[index], { value -> phrases = phrases.toMutableList().also { it[index] = value.take(180) } },
                    Modifier.fillMaxWidth(), label = { Text("搭配 ${index + 1}") }, singleLine = true, enabled = !busy)
                OutlinedTextField(examples[index], { value -> examples = examples.toMutableList().also { it[index] = value.take(600) } },
                    Modifier.fillMaxWidth(), label = { Text("AI 例示 ${index + 1}") }, minLines = 2, enabled = !busy)
            }
            OutlinedTextField(paraphrase, { paraphrase = it.take(600) }, Modifier.fillMaxWidth(), label = { Text("替换表达") }, minLines = 2, enabled = !busy)
            FieldLabel("语域")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("formal", "neutral", "informal").forEach { value -> FilterChip(register == value, { register = value }, label = { Text(registerLabel(value)) }, enabled = !busy) }
            }
            FieldLabel("考试相关度")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("high", "medium", "low").forEach { value -> FilterChip(relevance == value, { relevance = value }, label = { Text(relevanceLabel(value)) }, enabled = !busy) }
            }
            val edited = card.content.copy(headword = headword.trim(), contextDefinition = definition.trim(), paraphrase = paraphrase.trim(),
                collocations = phrases.indices.map { Collocation(phrases[it].trim(), examples[it].trim()) }, registerTag = register, examRelevance = relevance)
            val errors = ContentValidator.validate(edited)
            if (errors.isNotEmpty()) Text(errors.first(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && errors.isEmpty(), modifier = Modifier.testTag("card_save_edit"), onClick = {
                    onSave(card.copy(content = edited, updatedAt = System.currentTimeMillis())) { editing = false }
                }) { Text("保存修改") }
                TextButton(enabled = !busy, onClick = {
                    editing = false; headword = card.content.headword; definition = card.content.contextDefinition; paraphrase = card.content.paraphrase
                    phrases = card.content.collocations.map { it.phrase }; examples = card.content.collocations.map { it.example }
                    register = card.content.registerTag; relevance = card.content.examRelevance
                }) { Text("取消") }
            }
        } else {
            AnalysisContent(card.content)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { editing = true }, modifier = Modifier.testTag("card_edit_analysis")) { Text("编辑分析") }
                TextButton(enabled = !busy, onClick = { onSave(card.copy(inaccurate = !card.inaccurate, updatedAt = System.currentTimeMillis())) {} }) {
                    Text(if (card.inaccurate) "清除标记" else "标记为不准确")
                }
            }
        }
        HorizontalDivider()
        Text("${if (card.reviewPaused) "原到期时间" else "下次复习"} ${dateTimeLabel(card.state.dueAt)}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("card_review_due"))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun SourceScreen(sources: List<Source>, busy: Boolean, onToggle: (String, Boolean) -> Unit, onSync: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var subscribedOnly by rememberSaveable { mutableStateOf(false) }
    var syncInfoOpen by rememberSaveable { mutableStateOf(false) }
    val subscribedCount = sources.count { it.enabled }
    val shown = remember(sources, query, subscribedOnly) {
        val terms = query.trim().lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        sources.filter { source ->
            (!subscribedOnly || source.enabled) && terms.all { term -> sourceSearchText(source).contains(term) }
        }.sortedWith(compareBy<Source> { if (it.readingMode == ReadingMode.NATIVE) 0 else if (it.id.startsWith("economist-")) 1 else 2 }
            .thenBy { if (it.id == "economist-latest") 0 else 1 }.thenBy { it.name })
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("来源", style = MaterialTheme.typography.titleLarge)
                    Text("已订阅 $subscribedCount", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = !busy && subscribedCount > 0, onClick = onSync) { Text(if (busy) "同步中…" else "↓ 同步") }
            }
            OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth().testTag("source_search"), singleLine = true,
                placeholder = { Text("搜索来源或主题", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Text("/", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) },
                trailingIcon = if (query.isNotEmpty()) ({ TextButton(onClick = { query = "" }) { Text("清除", style = MaterialTheme.typography.labelSmall) } }) else null,
                shape = RoundedCornerShape(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!subscribedOnly, { subscribedOnly = false }, label = { Text("全部") }, modifier = Modifier.testTag("sources_all"))
                FilterChip(subscribedOnly, { subscribedOnly = true }, label = { Text("已订阅") }, modifier = Modifier.testTag("sources_subscribed"))
                Spacer(Modifier.weight(1f))
                Text("${shown.size} 项", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (shown.isEmpty()) {
            EmptyPanel(if (subscribedOnly && subscribedCount == 0) "还没有订阅来源" else "暂无匹配来源",
                if (subscribedOnly && subscribedCount == 0) "从全部来源中选择感兴趣的出版物。" else "试试其他关键词。",
                "查看全部来源") { query = ""; subscribedOnly = false }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shown, key = { it.id }) { source ->
                var detailsOpen by rememberSaveable(source.id) { mutableStateOf(false) }
                OutlinedCard(Modifier.fillMaxWidth().animateItem(),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(source.name, style = MaterialTheme.typography.titleMedium)
                                Text(if (source.readingMode == ReadingMode.NATIVE) "原生阅读" else "App 内阅读",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (source.readingMode == ReadingMode.NATIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = source.enabled, onCheckedChange = { onToggle(source.id, it) }, enabled = !busy,
                                modifier = Modifier.testTag("source_toggle_${source.id}"))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                source.defaultTopic?.let { InlineTag(sourceTopicLabel(it)) }
                                if (source.lastError != null) InlineTag("同步失败")
                            }
                            TextButton(onClick = { detailsOpen = !detailsOpen }, contentPadding = PaddingValues(horizontal = 2.dp)) {
                                Text(if (detailsOpen) "收起 ▴" else "详情 ▾", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        AnimatedVisibility(detailsOpen, enter = expandVertically(tween(200)) + fadeIn(tween(150)),
                            exit = shrinkVertically(tween(160)) + fadeOut(tween(100))) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                Text(if (source.readingMode == ReadingMode.NATIVE) "使用订阅源提供的正文，缓存保留 30 天。" else "在 App 内打开出版方网页。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (source.aiAllowed) "支持本地查词与 AI 语境分析。" else "支持本地查词；AI 处理未开启。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (source.license.isNotBlank()) Text(source.license, style = MaterialTheme.typography.bodySmall)
                                SelectionContainer { Text(source.feedUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text("最近检查：${dateTimeLabel(source.lastFetchedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (source.lastError != null) Text("上次同步失败，可以立即同步重试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            item(key = "source_sync_note") {
                Column {
                    TextButton(onClick = { syncInfoOpen = !syncInfoOpen }) {
                        Text(if (syncInfoOpen) "收起同步说明 ▴" else "同步说明 ▾", style = MaterialTheme.typography.labelSmall)
                    }
                    AnimatedVisibility(syncInfoOpen, enter = expandVertically(tween(180)) + fadeIn(), exit = shrinkVertically(tween(140)) + fadeOut()) {
                        Text("后台每 12 小时在充电与 Wi-Fi 条件下检查。关闭来源后，已有摘录和卡片仍会保留。",
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Local search aliases only; these labels do not infer source licensing or article topics. */
internal fun sourceSearchText(source: Source): String {
    val base = listOf(source.id, source.name, source.feedUrl, source.defaultTopic.orEmpty()).joinToString(" ").lowercase(java.util.Locale.ROOT)
    val aliases = sourceSearchAliases.filterKeys { base.contains(it) }.values.joinToString(" ")
    return "$base $aliases"
}

private val sourceSearchAliases = linkedMapOf(
    "conversation" to "对话 学术 专家", "bbc" to "英国广播公司 英广 英国广播",
    "guardian" to "卫报 英国卫报", "economist" to "经济学人 经济学人杂志",
    "new york times" to "纽约时报 纽时", "nytimes" to "纽约时报 纽时",
    "financial times" to "金融时报 英国金融时报", "ft.com" to "金融时报 英国金融时报",
    "atlantic" to "大西洋 大西洋月刊", "wired" to "连线 连线杂志",
    "mit-tech" to "麻省理工 科技评论", "ars-technica" to "科技 技术",
    "reuters" to "路透 路透社", "npr" to "美国国家公共广播 公共广播",
    "nature" to "自然 自然期刊", "scientific american" to "科学美国人 环球科学",
    "smithsonian" to "史密森尼", "national geographic" to "国家地理",
    "nasa" to "美国航天局 美国宇航局 太空", "science" to "科学 科技",
    "technology" to "科技 技术", "business" to "商业 财经", "finance" to "金融 财经",
    "economics" to "经济 财经", "politics" to "政治 时政", "world" to "国际 世界",
    "environment" to "环境 生态", "climate" to "气候", "health" to "健康 医疗",
    "culture" to "文化 艺术", "education" to "教育 学习", "law" to "法律 社会",
    "society" to "社会", "space" to "太空 航天", "arts" to "艺术", "energy" to "能源",
)

private fun sourceTopicLabel(topic: String): String = when (topic) {
    "Politics" -> "政治"; "Business" -> "商业"; "Science & Technology" -> "科学与科技"
    "Culture" -> "文化"; "Health" -> "健康"; "Environment" -> "环境"
    "Education" -> "教育"; "Law & Society" -> "法律与社会"
    else -> topic
}

@Composable
internal fun SettingsScreen(
    settings: AppSettings, hasKey: Boolean, busy: Boolean, jobs: List<AiJob>, onSettings: (AppSettings) -> Unit,
    onKey: (String) -> Unit, onRemoveKey: () -> Unit, onMetrics: () -> Unit, onExport: () -> Unit,
    dictionaryStatus: DictionaryStatus = DictionaryStatus(), onImportDictionary: () -> Unit = {},
    onImportApiKey: () -> Unit = {},
    dictionaries: List<InstalledDictionary> = emptyList(), onSelectDictionary: (String) -> Unit = {},
) {
    // Deliberately not rememberSaveable: secrets never enter the saved-state Bundle.
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        SettingsSection("词典") {
            if (dictionaries.isNotEmpty()) {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    dictionaries.forEach { dictionary ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (dictionary.selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerLow)
                            .selectable(selected = dictionary.selected, enabled = !busy, role = Role.RadioButton,
                                onClick = { onSelectDictionary(dictionary.id) })
                            .testTag("dictionary_choice_${dictionary.id}").padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RadioButton(selected = dictionary.selected, onClick = null, enabled = !busy)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(dictionary.name.ifBlank { "词典" }, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text("${dictionary.format} · ${dictionary.entryCount} 词条", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else if (dictionaryStatus.ready) {
                Text(dictionaryStatus.name.ifBlank { "词典" }, style = MaterialTheme.typography.bodyMedium)
                Text("${dictionaryStatus.entryCount} 词条 · 离线可查", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("尚未导入词典", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(enabled = !busy, onClick = onImportDictionary, modifier = Modifier.fillMaxWidth()) {
                Text("导入词典")
            }
            Text("MDX / SQLite · 最大 256 MiB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSection("DeepSeek") {
            Text(if (hasKey) "已配置 · 本机加密保存" else "尚未配置", style = MaterialTheme.typography.labelLarge,
                color = if (hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().testTag("api_key"), label = { Text(if (hasKey) "替换 API Key" else "输入 API Key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), shape = RoundedCornerShape(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = key.isNotBlank() && !busy, onClick = { onKey(key); key = "" }) { Text("保存") }
                OutlinedButton(enabled = !busy, onClick = onImportApiKey) { Text("导入 Key") }
                if (hasKey) TextButton(enabled = !busy, onClick = onRemoveKey) { Text("移除") }
            }
            Text("阅读辅助将原文或确认片段发送至 DeepSeek，按用量计费。背景检索仅向 Wikipedia 发送所选概念。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            SettingToggle("自动分类", "仅发送标题与来源标签。", settings.titleClassification) {
                onSettings(settings.copy(titleClassification = it))
            }
        }
        SettingsSection("外观与阅读") {
            SettingToggle("自动阅读辅助", "打开文章后生成导图与备考高亮，按 API 用量计费。", settings.reader.automaticStudy) {
                onSettings(settings.copy(reader = settings.reader.copy(automaticStudy = it)))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "Claude Code", ThemeMode.DARK to "Dracula").forEach { (mode, name) ->
                    FilterChip(settings.theme == mode, { onSettings(settings.copy(theme = mode)) }, label = { Text(name) })
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                Text("The results were consistent across both groups.", Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (settings.serif) ReadingSerif else EditorMono))
            }
            SettingToggle("衬线正文", "JetBrains Mono / Source Serif", settings.serif) { onSettings(settings.copy(serif = it)) }
            SettingToggle("夜间暖纸色", "深暖灰背景", settings.warmPaper) { onSettings(settings.copy(warmPaper = it)) }
            SettingToggle("专注计时", "90 秒未捕获后显示", settings.focusTimer) { onSettings(settings.copy(focusTimer = it)) }
        }
        SettingsSection("数据") {
            Text("原生正文缓存 30 天。摘录和卡片保存在本机，卸载后会移除。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(enabled = !busy, onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("导出到 Anki") }
            TextButton(enabled = !busy, onClick = onMetrics, modifier = Modifier.fillMaxWidth()) { Text("导出使用统计") }
            val unfinished = jobs.count { it.status.lowercase() !in setOf("done", "completed", "succeeded", "success") }
            if (unfinished > 0) Text("$unfinished 个任务未完成，可从捕获记录重试。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable private fun SettingToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable internal fun FieldLabel(label: String) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable internal fun QuoteBlock(text: String) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)))
        SelectionContainer { Text(text, Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingSerif, fontSize = 19.sp, lineHeight = 28.sp)) }
    }
}

@Composable internal fun AnalysisContent(content: VocabularyContent) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FieldLabel("AI 释义")
        SelectionContainer { Text(content.contextDefinition, style = MaterialTheme.typography.bodyLarge) }
        FieldLabel("搭配 · AI 例示")
        content.collocations.forEach { collocation ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f), RoundedCornerShape(12.dp)).padding(16.dp)) {
                Text(collocation.phrase, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(7.dp))
                Text(collocation.example, style = MaterialTheme.typography.bodyMedium)
            }
        }
        FieldLabel("替换表达")
        Text(content.paraphrase, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InlineTag(registerLabel(content.registerTag))
            InlineTag(examRelevanceLabel(content.examRelevance))
        }
    }
}

private fun registerLabel(value: String): String = when (value) {
    "formal" -> "正式"; "neutral" -> "中性"; "informal" -> "非正式"; else -> value
}

private fun relevanceLabel(value: String): String = when (value) {
    "high" -> "高"; "medium" -> "中"; "low" -> "低"; else -> value
}

private fun examRelevanceLabel(value: String): String = "考试参考 · ${relevanceLabel(value)}"

internal fun dateLabel(time: Long?): String = time?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd"))
} ?: "日期未提供"
internal fun dateTimeLabel(time: Long?): String = time?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
} ?: "尚无记录"
internal fun cardStateLabel(card: LearningCard): String = when {
    card.reviewPaused -> "已暂停"
    card.state.phase == CardPhase.NEW -> "新卡片"
    card.state.dueAt <= System.currentTimeMillis() -> "待复习"
    card.state.phase == CardPhase.REVIEW -> "复习中"
    else -> "学习中"
}
