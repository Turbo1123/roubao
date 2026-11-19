package com.roubao.autopilot.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roubao.autopilot.data.AppSettings
import com.roubao.autopilot.data.ChatMessage
import com.roubao.autopilot.data.ChatRepository
import com.roubao.autopilot.data.ChatSession
import com.roubao.autopilot.ui.theme.BaoziTheme
import com.roubao.autopilot.vlm.VLMClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

private const val CONTROL_MARKER = "@@CONTROL@@"

private const val CONTROL_ADDENDUM = "你还可以替用户操控这部手机（打开应用、点击、输入、发消息、搜索等）。" +
"当用户明确请你动手操作手机时，先用角色的口吻正常回复，然后在回复的最后一行单独输出一行：" +
"@@CONTROL@@ 后面紧跟一条具体明确的操作指令，例如「@@CONTROL@@ 打开微信，给妈妈发一条消息说今晚回家吃饭」。" +
"不需要操控时绝不要输出这个标记，也不要向用户解释或提及这个标记。"

private const val DEFAULT_PERSONA = "你是一个亲切自然的聊天伙伴，正在和用户进行角色扮演对话。" +
"保持角色一致，回复自然口语化，不要说自己是 AI，也不要提及系统提示。"

private fun startOfDay(offsetDays: Int): Long {
val cal = Calendar.getInstance()
cal.add(Calendar.DAY_OF_YEAR, offsetDays)
cal.set(Calendar.HOUR_OF_DAY, 0)
cal.set(Calendar.MINUTE, 0)
cal.set(Calendar.SECOND, 0)
cal.set(Calendar.MILLISECOND, 0)
return cal.timeInMillis
}

private fun timeGroupLabel(timestamp: Long): String {
return when {
timestamp >= startOfDay(0) -> "今天"
timestamp >= startOfDay(-1) -> "昨天"
timestamp >= startOfDay(-7) -> "7天内"
else -> "更早"
}
}

/**
* 对话页：以角色扮演聊天为主，操控手机为辅助。
* 左侧抽屉是会话侧边栏：多会话切换、搜索、重命名、置顶、多选、删除（仿主流聊天 App）。
* 聊天记录本地保存；单个会话攒到 8000 字时用模型压缩较早的部分（最新约 10% 保留原文）。
*/
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
settings: AppSettings,
onControlRequest: (instruction: String, onResult: (Boolean, String) -> Unit) -> Unit,
onOpenSettings: () -> Unit
) {
val colors = BaoziTheme.colors
val context = LocalContext.current
val repository = remember { ChatRepository(context)}

var sessions by remember { mutableStateOf(repository.loadSessions())}
var currentId by remember {
mutableStateOf(
repository.getCurrentSessionId()?.takeIf { id -> sessions.any { it.id == id}}
?: sessions.maxByOrNull { it.updatedAt}?.id
)
}
// 首次进入且没有任何会话时，建一个空会话
if (sessions.isEmpty()) {
val created = ChatSession()
sessions = listOf(created)
currentId = created.id
repository.saveSessions(sessions)
repository.setCurrentSessionId(created.id)
}

val currentSession = sessions.firstOrNull { it.id == currentId}?: sessions.first()
var messages by remember { mutableStateOf(currentSession.messages)}
var summary by remember { mutableStateOf(currentSession.summary)}
var inputText by remember { mutableStateOf("")}
var isSending by remember { mutableStateOf(false)}
val listState = rememberLazyListState()
val scope = rememberCoroutineScope()
val keyboardController = LocalSoftwareKeyboardController.current
val focusManager = LocalFocusManager.current
val drawerState = rememberDrawerState(DrawerValue.Closed)

// 把当前会话的内存状态写回会话列表并落盘
fun syncCurrent() {
val now = System.currentTimeMillis()
sessions = sessions.map { session ->
if (session.id == currentId) {
var title = session.title
if (title == "新对话") {
val firstUser = messages.firstOrNull { it.role == "user"}
if (firstUser!= null) {
title = firstUser.content.replace("\n", " ").take(18)
}
}
session.copy(
title = title,
messages = messages,
summary = summary,
updatedAt = now
)
} else {
session
}
}
repository.saveSessions(sessions)
repository.setCurrentSessionId(currentId)
}

fun persist() {
syncCurrent()
}

fun switchSession(id: String) {
if (id == currentId || isSending) {
return
}
syncCurrent()
val target = sessions.firstOrNull { it.id == id}?: return
currentId = target.id
messages = target.messages
summary = target.summary
repository.setCurrentSessionId(target.id)
}

fun newSession() {
if (isSending) {
return
}
syncCurrent()
val current = sessions.firstOrNull { it.id == currentId}
// 当前会话还是空的就直接复用，避免堆积空会话
if (current!= null && current.messages.isEmpty() && current.summary.isBlank()) {
scope.launch { drawerState.close()}
return
}
val created = ChatSession()
sessions = listOf(created) + sessions
currentId = created.id
messages = emptyList()
summary = ""
repository.saveSessions(sessions)
repository.setCurrentSessionId(created.id)
scope.launch { drawerState.close()}
}

fun deleteSessions(ids: Set<String>) {
val remaining = sessions.filter { it.id!in ids}
sessions = if (remaining.isEmpty()) listOf(ChatSession()) else remaining
if (currentId in ids) {
val next = sessions.maxByOrNull { it.updatedAt}?: sessions.first()
currentId = next.id
messages = next.messages
summary = next.summary
}
repository.saveSessions(sessions)
repository.setCurrentSessionId(currentId)
}

fun renameSession(id: String, newTitle: String) {
val title = newTitle.trim()
if (title.isEmpty()) {
return
}
sessions = sessions.map { if (it.id == id) it.copy(title = title) else it}
repository.saveSessions(sessions)
}

fun togglePin(id: String) {
sessions = sessions.map { if (it.id == id) it.copy(pinned =!it.pinned) else it}
repository.saveSessions(sessions)
}

fun buildClient(): VLMClient = VLMClient(
apiKey = settings.apiKey,
baseUrl = settings.baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1"},
model = settings.model.ifBlank { "qwen3-vl-plus"}
)

fun buildMessagesJson(): JSONArray {
val array = JSONArray()
val system = buildString {
append(settings.customSystemPrompt.ifBlank { DEFAULT_PERSONA})
append("\n\n")
append(CONTROL_ADDENDUM)
if (summary.isNotBlank()) {
append("\n\n\n")
append(summary)
}
if (settings.longTermMemory.isNotBlank()) {
append("\n\n\n")
append(settings.longTermMemory)
}
}
array.put(JSONObject().apply {
put("role", "system")
put("content", system)
})
for (msg in messages) {
array.put(JSONObject().apply {
put("role", msg.role)
put("content", msg.content)
})
}
return array
}

// 对话攒到 8000 字时压缩：最新约 10% 保留原文，其余用模型总结成摘要
suspend fun compressIfNeeded(client: VLMClient) {
val total = messages.sumOf { it.content.length}
if (total <= 8000 || messages.size < 4) {
return
}
val keepTarget = (total * 0.10).toInt()
var acc = 0
var keepFrom = messages.size
for (i in messages.indices.reversed()) {
acc += messages[i].content.length
keepFrom = i
if (acc >= keepTarget) {
break
}
}
if (keepFrom <= 0) {
return
}
val oldText = buildString {
if (summary.isNotBlank()) {
append("\n")
append(summary)
append("\n\n")
}
append(messages.subList(0, keepFrom).joinToString("\n") { msg ->
val who = when (msg.role) {
"user" -> "用户"
"assistant" -> "角色"
else -> "注记"
}
"$who：${msg.content}"
})
}
val prompt = "你是一个对话记忆压缩助手。请把下面这段对话记录压缩成简洁摘要，要求：" +
"保留角色与用户的关系进展、共同经历的重要事件、用户的偏好与习惯、未完成的话题与约定；" +
"删掉琐碎的闲聊细节；用条目式短句输出，不超过原文的三分之一。只输出摘要正文，不要解释。\n\n对话记录：\n$oldText"
val digest = client.chatCompletion(
JSONArray().put(JSONObject().apply {
put("role", "user")
put("content", prompt)
}),
temperature = 0.3
).getOrNull()?.trim()
if (!digest.isNullOrBlank() && digest.length < oldText.length) {
summary = digest
messages = messages.subList(keepFrom, messages.size).toList()
persist()
}
}

suspend fun requestReply(client: VLMClient, allowControl: Boolean) {
val result = client.chatCompletion(buildMessagesJson())
result.onSuccess { raw ->
var text = raw.trim()
var control: String? = null
val idx = text.indexOf(CONTROL_MARKER)
if (idx >= 0) {
control = text.substring(idx + CONTROL_MARKER.length)
.trim().lineSequence().firstOrNull()?.trim()
text = text.substring(0, idx).trim()
}
if (text.isNotBlank()) {
messages = messages + ChatMessage("assistant", text)
persist()
}
if (allowControl &&!control.isNullOrBlank()) {
val instruction = control
messages = messages + ChatMessage("system", "（正在操控手机：$instruction）")
persist()
onControlRequest(instruction) { success, resultMessage ->
messages = messages + ChatMessage(
"system",
"（手机操作${if (success) "完成" else "未成功"}：$resultMessage）"
)
persist()
// 操控结束后让角色接着聊一句（这次不再触发操控）
scope.launch {
isSending = true
try {
requestReply(buildClient(), allowControl = false)
} finally {
isSending = false
}
}
}
}
}.onFailure { e ->
messages = messages + ChatMessage("system", "（回复失败：${e.message}）")
persist()
}
}

fun send() {
val text = inputText.trim()
if (text.isEmpty() || isSending) {
return
}
inputText = ""
keyboardController?.hide()
focusManager.clearFocus()
messages = messages + ChatMessage("user", text)
persist()
if (settings.apiKey.isBlank() && settings.currentProviderId!= "mai_ui") {
messages = messages + ChatMessage("system", "（还没配置 API：请先到「设置」里填好 API 地址和 Key）")
persist()
return
}
scope.launch {
isSending = true
try {
val client = buildClient()
compressIfNeeded(client)
requestReply(client, allowControl = true)
} finally {
isSending = false
}
}
}

LaunchedEffect(messages.size, isSending) {
val count = messages.size + if (isSending) 1 else 0
if (count > 0 && messages.isNotEmpty()) {
listState.animateScrollToItem(count - 1)
}
}

ModalNavigationDrawer(
drawerState = drawerState,
drawerContent = {
ModalDrawerSheet(
modifier = Modifier.width(300.dp),
drawerContainerColor = colors.background
) {
SessionDrawerContent(
sessions = sessions,
currentId = currentId,
onSwitch = { id ->
switchSession(id)
scope.launch { drawerState.close()}
},
onNew = { newSession()},
onRename = { id, title -> renameSession(id, title)},
onTogglePin = { id -> togglePin(id)},
onDelete = { ids -> deleteSessions(ids)},
onOpenSettings = {
scope.launch { drawerState.close()}
onOpenSettings()
}
)
}
}
) {
Column(
modifier = Modifier
.fillMaxSize()
.background(colors.background)
.imePadding()
) {
// 顶栏：侧边栏按钮 + 当前会话标题 + 新建
Row(
modifier = Modifier
.fillMaxWidth()
.padding(horizontal = 8.dp, vertical = 6.dp),
verticalAlignment = Alignment.CenterVertically
) {
IconButton(onClick = { scope.launch { drawerState.open()}}) {
Icon(
imageVector = Icons.Default.Menu,
contentDescription = "会话列表",
tint = colors.textPrimary
)
}
Text(
text = currentSession.title,
fontSize = 17.sp,
fontWeight = FontWeight.Bold,
color = colors.textPrimary,
textAlign = TextAlign.Center,
maxLines = 1,
overflow = TextOverflow.Ellipsis,
modifier = Modifier.weight(1f)
)
IconButton(onClick = { newSession()}) {
Icon(
imageVector = Icons.Default.Edit,
contentDescription = "新建对话",
tint = colors.textPrimary
)
}
}

if (messages.isEmpty()) {
// 空状态（参考聊天类 App 的开场）
Box(
modifier = Modifier
.weight(1f)
.fillMaxWidth(),
contentAlignment = Alignment.Center
) {
Column(horizontalAlignment = Alignment.CenterHorizontally) {
Box(
modifier = Modifier
.size(58.dp)
.clip(CircleShape)
.background(colors.primary),
contentAlignment = Alignment.Center
) {
Text(
text = "S",
fontSize = 30.sp,
fontWeight = FontWeight.Bold,
color = Color.White
)
}
Spacer(modifier = Modifier.height(16.dp))
Text(
text = "嗨！今天想聊些什么？",
fontSize = 20.sp,
fontWeight = FontWeight.Medium,
color = colors.textPrimary
)
Spacer(modifier = Modifier.height(8.dp))
Text(
text = "在「设置」里填好角色设定，我就是你要的那个角色",
fontSize = 13.sp,
color = colors.textHint
)
Text(
text = "聊天中让我操作手机，我也会照做",
fontSize = 13.sp,
color = colors.textHint
)
}
}
} else {
LazyColumn(
state = listState,
modifier = Modifier
.weight(1f)
.fillMaxWidth()
.padding(horizontal = 16.dp),
verticalArrangement = Arrangement.spacedBy(10.dp),
contentPadding = PaddingValues(vertical = 12.dp)
) {
items(messages) { msg ->
ChatBubble(msg)
}
if (isSending) {
item {
Text(
text = "正在输入…",
fontSize = 14.sp,
color = colors.textHint,
modifier = Modifier.padding(vertical = 4.dp)
)
}
}
}
}

// 底部输入区（两行卡片：上面输入、下面功能行，仿 DeepSeek）
Column(
modifier = Modifier
.fillMaxWidth()
.padding(horizontal = 16.dp, vertical = 10.dp)
.clip(RoundedCornerShape(24.dp))
.background(colors.backgroundInput)
.padding(horizontal = 16.dp, vertical = 12.dp)
) {
BasicTextField(
value = inputText,
onValueChange = { inputText = it},
textStyle = TextStyle(
color = colors.textPrimary,
fontSize = 15.sp
),
cursorBrush = SolidColor(colors.primary),
modifier = Modifier.fillMaxWidth(),
decorationBox = { innerTextField ->
Box {
if (inputText.isEmpty()) {
Text(
text = "发消息或按住说话",
color = colors.textHint,
fontSize = 15.sp
)
}
innerTextField()
}
}
)

Spacer(modifier = Modifier.height(12.dp))

Row(verticalAlignment = Alignment.CenterVertically) {
// 角色设定快捷入口（点开进设置页）
Surface(
shape = RoundedCornerShape(16.dp),
color = colors.backgroundCard,
modifier = Modifier.clickable { onOpenSettings()}
) {
Row(
modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
verticalAlignment = Alignment.CenterVertically
) {
Icon(
imageVector = Icons.Default.Person,
contentDescription = null,
tint = colors.textSecondary,
modifier = Modifier.size(15.dp)
)
Spacer(modifier = Modifier.width(5.dp))
Text(
text = "角色设定",
fontSize = 13.sp,
color = colors.textSecondary
)
}
}

Spacer(modifier = Modifier.weight(1f))

IconButton(
onClick = { send()},
enabled = inputText.isNotBlank() &&!isSending,
modifier = Modifier
.size(40.dp)
.clip(CircleShape)
.background(
if (inputText.isNotBlank() &&!isSending) colors.primary
else colors.backgroundCard
)
) {
Icon(
imageVector = Icons.Default.KeyboardArrowUp,
contentDescription = "发送",
tint = if (inputText.isNotBlank() &&!isSending) Color.White else colors.textHint,
modifier = Modifier.size(20.dp)
)
}
}
}
}
}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionDrawerContent(
sessions: List<ChatSession>,
currentId: String?,
onSwitch: (String) -> Unit,
onNew: () -> Unit,
onRename: (String, String) -> Unit,
onTogglePin: (String) -> Unit,
onDelete: (Set<String>) -> Unit,
onOpenSettings: () -> Unit
) {
val colors = BaoziTheme.colors
var query by remember { mutableStateOf("")}
var menuSession by remember { mutableStateOf<ChatSession?>(null)}
var renameTarget by remember { mutableStateOf<ChatSession?>(null)}
var renameText by remember { mutableStateOf("")}
var deleteTarget by remember { mutableStateOf<ChatSession?>(null)}
var multiSelectMode by remember { mutableStateOf(false)}
var selectedIds by remember { mutableStateOf<Set<String>>(emptySet())}
var showDeleteSelectedDialog by remember { mutableStateOf(false)}

val filtered = sessions.filter { session ->
if (query.isBlank()) {
true
} else {
session.title.contains(query, ignoreCase = true) ||
session.messages.any { it.content.contains(query, ignoreCase = true)}
}
}
val pinned = filtered.filter { it.pinned}.sortedByDescending { it.updatedAt}
val normal = filtered.filter {!it.pinned}.sortedByDescending { it.updatedAt}
val groups = mutableListOf<Pair<String, List<ChatSession>>>()
if (pinned.isNotEmpty()) {
groups.add("置顶" to pinned)
}
for (label in listOf("今天", "昨天", "7天内", "更早")) {
val inGroup = normal.filter { timeGroupLabel(it.updatedAt) == label}
if (inGroup.isNotEmpty()) {
groups.add(label to inGroup)
}
}

Column(modifier = Modifier.fillMaxSize()) {
// 搜索框
Box(
modifier = Modifier
.fillMaxWidth()
.padding(horizontal = 16.dp, vertical = 12.dp)
.clip(RoundedCornerShape(20.dp))
.background(colors.backgroundCard)
.padding(horizontal = 14.dp, vertical = 10.dp)
) {
Row(verticalAlignment = Alignment.CenterVertically) {
Icon(
imageVector = Icons.Default.Search,
contentDescription = null,
tint = colors.textHint,
modifier = Modifier.size(18.dp)
)
Spacer(modifier = Modifier.width(8.dp))
BasicTextField(
value = query,
onValueChange = { query = it},
singleLine = true,
textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
cursorBrush = SolidColor(colors.primary),
modifier = Modifier.weight(1f),
decorationBox = { innerTextField ->
Box {
if (query.isEmpty()) {
Text("搜索对话内容…", color = colors.textHint, fontSize = 14.sp)
}
innerTextField()
}
}
)
if (query.isNotEmpty()) {
IconButton(
onClick = { query = ""},
modifier = Modifier.size(20.dp)
) {
Icon(
imageVector = Icons.Default.Close,
contentDescription = "清空搜索",
tint = colors.textHint,
modifier = Modifier.size(16.dp)
)
}
}
}
}

if (multiSelectMode) {
// 多选模式顶栏
Row(
modifier = Modifier
.fillMaxWidth()
.padding(horizontal = 16.dp, vertical = 4.dp),
verticalAlignment = Alignment.CenterVertically
) {
Text(
text = "已选 ${selectedIds.size} 个",
fontSize = 14.sp,
color = colors.textPrimary,
modifier = Modifier.weight(1f)
)
TextButton(
onClick = { if (selectedIds.isNotEmpty()) showDeleteSelectedDialog = true},
enabled = selectedIds.isNotEmpty()
) {
Text("删除", color = colors.error)
}
TextButton(onClick = {
multiSelectMode = false
selectedIds = emptySet()
}) {
Text("取消", color = colors.textSecondary)
}
}
} else {
// 新建对话
Row(
modifier = Modifier
.fillMaxWidth()
.padding(horizontal = 16.dp, vertical = 4.dp),
verticalAlignment = Alignment.CenterVertically
) {
TextButton(onClick = onNew) {
Icon(
imageVector = Icons.Default.Add,
contentDescription = null,
tint = colors.primary,
modifier = Modifier.size(18.dp)
)
Spacer(modifier = Modifier.width(4.dp))
Text("新建对话", color = colors.primary, fontSize = 15.sp)
}
}
}

// 会话列表（按时间分组）
LazyColumn(
modifier = Modifier
.weight(1f)
.fillMaxWidth()
) {
for ((label, groupSessions) in groups) {
item(key = "header-$label") {
Text(
text = label,
fontSize = 12.sp,
color = colors.textHint,
modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
)
}
items(groupSessions, key = { it.id}) { session ->
val isCurrent = session.id == currentId
Box {
Row(
modifier = Modifier
.fillMaxWidth()
.padding(horizontal = 8.dp, vertical = 2.dp)
.clip(RoundedCornerShape(12.dp))
.background(if (isCurrent) colors.backgroundCard else Color.Transparent)
.combinedClickable(
onClick = {
if (multiSelectMode) {
selectedIds = if (session.id in selectedIds) {
selectedIds - session.id
} else {
selectedIds + session.id
}
} else {
onSwitch(session.id)
}
},
onLongClick = {
if (!multiSelectMode) {
menuSession = session
}
}
)
.padding(horizontal = 12.dp, vertical = 12.dp),
verticalAlignment = Alignment.CenterVertically
) {
if (multiSelectMode) {
Checkbox(
checked = session.id in selectedIds,
onCheckedChange = { checked ->
selectedIds = if (checked) {
selectedIds + session.id
} else {
selectedIds - session.id
}
},
modifier = Modifier.size(22.dp)
)
Spacer(modifier = Modifier.width(8.dp))
}
Text(
text = session.title,
fontSize = 15.sp,
color = colors.textPrimary,
maxLines = 1,
overflow = TextOverflow.Ellipsis,
modifier = Modifier.weight(1f)
)
if (session.pinned) {
Spacer(modifier = Modifier.width(6.dp))
Icon(
imageVector = Icons.Default.Star,
contentDescription = "已置顶",
tint = colors.primary,
modifier = Modifier.size(14.dp)
)
}
}

// 长按菜单：重命名 / 置顶 / 多选 / 删除
DropdownMenu(
expanded = menuSession?.id == session.id,
onDismissRequest = { menuSession = null},
modifier = Modifier.background(colors.surfaceVariant)
) {
DropdownMenuItem(
text = { Text("重命名", color = colors.textPrimary)},
leadingIcon = {
Icon(Icons.Default.Edit, contentDescription = null, tint = colors.textSecondary)
},
onClick = {
renameText = session.title
renameTarget = session
menuSession = null
}
)
DropdownMenuItem(
text = { Text(if (session.pinned) "取消置顶" else "置顶", color = colors.textPrimary)},
leadingIcon = {
Icon(Icons.Default.Star, contentDescription = null, tint = colors.textSecondary)
},
onClick = {
onTogglePin(session.id)
menuSession = null
}
)
DropdownMenuItem(
text = { Text("多选", color = colors.textPrimary)},
leadingIcon = {
Icon(Icons.Default.Check, contentDescription = null, tint = colors.textSecondary)
},
onClick = {
multiSelectMode = true
selectedIds = setOf(session.id)
menuSession = null
}
)
DropdownMenuItem(
text = { Text("删除", color = colors.error)},
leadingIcon = {
Icon(Icons.Default.Delete, contentDescription = null, tint = colors.error)
},
onClick = {
deleteTarget = session
menuSession = null
}
)
}
}
}
}
if (groups.isEmpty()) {
item {
Text(
text = "没有找到对话",
fontSize = 14.sp,
color = colors.textHint,
modifier = Modifier.padding(16.dp)
)
}
}
}

// 底部：账号位样式的设置入口（点开进设置页，仿 DeepSeek 左下角）
Row(
modifier = Modifier
.fillMaxWidth()
.clickable { onOpenSettings()}
.padding(horizontal = 16.dp, vertical = 12.dp),
verticalAlignment = Alignment.CenterVertically
) {
Box(
modifier = Modifier
.size(38.dp)
.clip(CircleShape)
.background(colors.backgroundCard),
contentAlignment = Alignment.Center
) {
Icon(
imageVector = Icons.Default.Settings,
contentDescription = "设置",
tint = colors.textPrimary,
modifier = Modifier.size(20.dp)
)
}
Spacer(modifier = Modifier.width(12.dp))
Text(
text = "设置",
fontSize = 15.sp,
fontWeight = FontWeight.Medium,
color = colors.textPrimary,
modifier = Modifier.weight(1f)
)
Text(
text = "···",
fontSize = 18.sp,
fontWeight = FontWeight.Bold,
color = colors.textSecondary
)
}
}

// 重命名对话框
if (renameTarget!= null) {
AlertDialog(
onDismissRequest = { renameTarget = null},
containerColor = colors.backgroundCard,
title = { Text("重命名对话", color = colors.textPrimary)},
text = {
OutlinedTextField(
value = renameText,
onValueChange = { renameText = it},
singleLine = true,
textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp)
)
},
confirmButton = {
TextButton(onClick = {
renameTarget?.let { onRename(it.id, renameText)}
renameTarget = null
}) {
Text("确定", color = colors.primary)
}
},
dismissButton = {
TextButton(onClick = { renameTarget = null}) {
Text("取消", color = colors.textSecondary)
}
}
)
}

// 删除单个对话确认
if (deleteTarget!= null) {
AlertDialog(
onDismissRequest = { deleteTarget = null},
containerColor = colors.backgroundCard,
title = { Text("删除对话", color = colors.textPrimary)},
text = {
Text(
"确定要删除「${deleteTarget?.title}」吗？删除后无法恢复。",
color = colors.textSecondary,
fontSize = 14.sp
)
},
confirmButton = {
TextButton(onClick = {
deleteTarget?.let { onDelete(setOf(it.id))}
deleteTarget = null
}) {
Text("删除", color = colors.error)
}
},
dismissButton = {
TextButton(onClick = { deleteTarget = null}) {
Text("取消", color = colors.textSecondary)
}
}
)
}

// 多选删除确认
if (showDeleteSelectedDialog) {
AlertDialog(
onDismissRequest = { showDeleteSelectedDialog = false},
containerColor = colors.backgroundCard,
title = { Text("删除对话", color = colors.textPrimary)},
text = {
Text(
"确定要删除选中的 ${selectedIds.size} 个对话吗？删除后无法恢复。",
color = colors.textSecondary,
fontSize = 14.sp
)
},
confirmButton = {
TextButton(onClick = {
onDelete(selectedIds)
selectedIds = emptySet()
multiSelectMode = false
showDeleteSelectedDialog = false
}) {
Text("删除", color = colors.error)
}
},
dismissButton = {
TextButton(onClick = { showDeleteSelectedDialog = false}) {
Text("取消", color = colors.textSecondary)
}
}
)
}
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
val colors = BaoziTheme.colors
when (msg.role) {
"user" -> {
// 用户消息：浅灰蓝气泡（DeepSeek 式）
Box(
modifier = Modifier.fillMaxWidth(),
contentAlignment = Alignment.CenterEnd
) {
Surface(
shape = RoundedCornerShape(18.dp),
color = colors.backgroundInput,
modifier = Modifier.widthIn(max = 300.dp)
) {
Text(
text = msg.content,
fontSize = 15.sp,
color = colors.textPrimary,
modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
)
}
}
}
"assistant" -> {
// 助手消息：无气泡纯文本（DeepSeek 式）
Box(
modifier = Modifier.fillMaxWidth(),
contentAlignment = Alignment.CenterStart
) {
Text(
text = msg.content,
fontSize = 15.sp,
color = colors.textPrimary,
modifier = Modifier
.widthIn(max = 340.dp)
.padding(vertical = 2.dp)
)
}
}
else -> {
Text(
text = msg.content,
fontSize = 12.sp,
color = colors.textHint,
textAlign = TextAlign.Center,
modifier = Modifier
.fillMaxWidth()
.padding(vertical = 2.dp)
)
}
}
}
