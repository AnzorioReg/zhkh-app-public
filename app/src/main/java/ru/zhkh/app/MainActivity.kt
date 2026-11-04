package ru.zhkh.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZhkhApp() }
    }
}

private data class Payment(val category: String, val amount: Double, val date: String, val paid: Boolean = true)
private data class AppTheme(val name: String, val dark: Boolean, val primary: Color, val background: Color, val surface: Color)
private data class AddressResult(val title: String, val subtitle: String)

private val themes = listOf(
    AppTheme("Aurora Light", false, Color(0xFF00695C), Color(0xFFF7FAF9), Color.White),
    AppTheme("Ivory Gold", false, Color(0xFF8D6E63), Color(0xFFFBF7F0), Color.White),
    AppTheme("Nordic Blue", false, Color(0xFF1565C0), Color(0xFFF4F7FB), Color.White),
    AppTheme("Rose Quartz", false, Color(0xFFAD1457), Color(0xFFFDF5F7), Color.White),
    AppTheme("Emerald Day", false, Color(0xFF2E7D32), Color(0xFFF3FAF4), Color.White),
    AppTheme("Midnight Neon", true, Color(0xFF22D3EE), Color(0xFF0B1220), Color(0xFF111C2E)),
    AppTheme("Deep Space", true, Color(0xFF8B5CF6), Color(0xFF0A0A12), Color(0xFF141424)),
    AppTheme("Carbon Gold", true, Color(0xFFF59E0B), Color(0xFF121212), Color(0xFF1E1E1E)),
    AppTheme("Ocean Night", true, Color(0xFF38BDF8), Color(0xFF0C1A2B), Color(0xFF13253A)),
    AppTheme("Emerald Night", true, Color(0xFF34D399), Color(0xFF0A1512), Color(0xFF12201B))
)

private val logos = listOf("🏢", "💧", "🔥", "⚡", "🧾", "🏠", "📊", "🔌", "🛠️", "🌐")
private val categoryColors = mapOf(
    "Электричество" to Color(0xFFF59E0B),
    "Газ" to Color(0xFF3B82F6),
    "Холодная вода" to Color(0xFF06B6D4),
    "Горячая вода" to Color(0xFFEF4444),
    "Отопление" to Color(0xFFF97316),
    "Содержание жилья" to Color(0xFF8B5CF6),
    "Интернет" to Color(0xFF10B981)
)

@Composable
fun ZhkhApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zhkh", Context.MODE_PRIVATE) }
    var themeIndex by remember { mutableIntStateOf(prefs.getInt("theme", 5)) }
    var logoIndex by remember { mutableIntStateOf(prefs.getInt("logo", 0)) }
    var address by remember { mutableStateOf(prefs.getString("address", "") ?: "") }
    var payerCode by remember { mutableStateOf(prefs.getString("payerCode", "") ?: "") }
    var apiUrl by remember { mutableStateOf(prefs.getString("apiUrl", "") ?: "") }
    var apiToken by remember { mutableStateOf(prefs.getString("apiToken", "") ?: "") }
    var payments by remember { mutableStateOf(loadPayments(prefs)) }
    var status by remember { mutableStateOf("") }
    var screen by remember { mutableIntStateOf(0) }
    var showSources by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val theme = themes[themeIndex]

    MaterialTheme(
        colorScheme = if (theme.dark) {
            darkColorScheme(primary = theme.primary, background = theme.background, surface = theme.surface, surfaceVariant = theme.surface)
        } else {
            lightColorScheme(primary = theme.primary, background = theme.background, surface = theme.surface, surfaceVariant = theme.surface)
        }
    ) {
        if (address.isBlank()) {
            AddressSetupScreen(prefs, address, payerCode, onAddress = { address = it }, onCode = { payerCode = it }, onContinue = {
                prefs.edit().putString("address", address).putString("payerCode", payerCode).apply()
            })
        } else if (showSources) {
            SourcesScreen(onBack = { showSources = false }, onGosuslugi = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dom.gosuslugi.ru/"))) }, onEpd = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.mos.ru/pgu2/landing/172687/"))) })
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        listOf("Главная" to Icons.Default.Home, "Аналитика" to Icons.Default.Info, "Платежи" to Icons.Default.List, "Ещё" to Icons.Default.Settings).forEachIndexed { index, item ->
                            NavigationBarItem(selected = screen == index, onClick = { screen = index }, icon = { Icon(item.second, contentDescription = null) }, label = { Text(item.first) })
                        }
                    }
                }
            ) { padding ->
                Column(modifier = Modifier.padding(padding)) {
                    when (screen) {
                        0 -> HomeScreen(address, year, payments, status, logoIndex, onSources = { showSources = true }, onDemo = { payments = demoPayments(); savePayments(prefs, payments); status = "Демо-данные загружены" }, onSync = { scope.launch { status = syncApi(apiUrl, apiToken) { result -> payments = result; savePayments(prefs, result) } } })
                        1 -> AnalyticsScreen(year, payments)
                        2 -> PaymentsScreen(payments) { category, amount, date -> payments = payments + Payment(category, amount, date); savePayments(prefs, payments) }
                        3 -> SettingsScreen(themeIndex, logoIndex, apiUrl, apiToken, onTheme = { themeIndex = it; prefs.edit().putInt("theme", it).apply() }, onLogo = { logoIndex = it; prefs.edit().putInt("logo", it).apply() }, onApi = { apiUrl = it }, onToken = { apiToken = it }, onSave = { prefs.edit().putString("apiUrl", apiUrl).putString("apiToken", apiToken).apply() }, onShare = { shareReport(context, year, payments) }, onReset = { payments = emptyList(); savePayments(prefs, payments) })
                    }
                }
            }
        }
    }
}

@Composable
fun AddressSetupScreen(prefs: android.content.SharedPreferences, address: String, code: String, onAddress: (String) -> Unit, onCode: (String) -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<AddressResult>()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(text = logos[prefs.getInt("logo", 0)], fontSize = 52.sp)
        Text(text = "ЖКХ Плюс", fontSize = 38.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text(text = "Найдите квартиру и соберите все данные в одном месте", color = Color(0xFF94A3B8), modifier = Modifier.padding(top = 6.dp))
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Город, улица, дом") }, trailingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp), shape = RoundedCornerShape(16.dp))
        Button(onClick = { loading = true; scope.launch { results = searchAddress(query); loading = false } }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(if (loading) "Поиск..." else "Найти адрес") }
        results.take(5).forEach { result -> Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onAddress(result.title); onContinue() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(modifier = Modifier.padding(14.dp)) { Text(result.title, fontWeight = FontWeight.Bold); Text(result.subtitle, fontSize = 12.sp, color = Color(0xFF94A3B8)) } } }
        OutlinedTextField(value = address, onValueChange = onAddress, label = { Text("Адрес вручную") }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        OutlinedTextField(value = code, onValueChange = onCode, label = { Text("Код плательщика / лицевой счёт") }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        Button(onClick = { onContinue(); context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://esia.gosuslugi.ru/login/"))) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Войти через Госуслуги") }
    }
}

@Composable
fun HomeScreen(address: String, year: Int, payments: List<Payment>, status: String, logoIndex: Int, onSources: () -> Unit, onDemo: () -> Unit, onSync: () -> Unit) {
    val yearly = payments.filter { it.date.startsWith(year.toString()) && it.paid }
    val total = yearly.sumOf { it.amount }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text(text = logos[logoIndex], fontSize = 34.sp); Column(modifier = Modifier.padding(start = 12.dp)) { Text("Моя квартира", fontSize = 12.sp, color = Color(0xFF94A3B8)); Text(address, fontSize = 18.sp, fontWeight = FontWeight.Bold) } } }
        item { Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(26.dp)) { Column(modifier = Modifier.padding(22.dp)) { Text("Оплачено за $year", color = Color(0xFF94A3B8)); Text("%,.0f ₽".format(total), fontSize = 40.sp, fontWeight = FontWeight.Black); Text("${yearly.size} платежей • $year год", color = MaterialTheme.colorScheme.primary) } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = onSources, modifier = Modifier.weight(1f)) { Text("Подключить данные") }; OutlinedButton(onClick = onSync, modifier = Modifier.weight(1f)) { Text("Синхронизация") } } }
        item { OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, contentDescription = null); Text("  Показать демо-аналитику") } }
        if (status.isNotEmpty()) { item { Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) } }
        item { Text("Последние платежи", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        items(yearly.takeLast(6).reversed()) { payment -> PaymentCard(payment) }
    }
}

@Composable
fun PaymentCard(payment: Payment) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(categoryColors[payment.category] ?: Color(0xFF64748B)))
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) { Text(payment.category, fontWeight = FontWeight.Bold); Text(payment.date, fontSize = 12.sp, color = Color(0xFF94A3B8)) }
            Text("%,.0f ₽".format(payment.amount), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AnalyticsScreen(year: Int, payments: List<Payment>) {
    val data = payments.filter { it.date.startsWith(year.toString()) && it.paid }.groupBy { it.category }.mapValues { entry -> entry.value.sumOf { it.amount } }.toList().sortedByDescending { it.second }
    val max = data.maxOfOrNull { it.second } ?: 1.0
    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Сводка за $year", fontSize = 28.sp, fontWeight = FontWeight.Black); Text("Куда уходят ваши деньги", color = Color(0xFF94A3B8)) }
        item { Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(26.dp)) { Column(modifier = Modifier.padding(20.dp)) { if (data.isEmpty()) Text("Нет данных. Нажмите «Показать демо-аналитику» на главной.", color = Color(0xFF94A3B8)) else data.forEach { (name, value) -> CategoryBar(name, value, max) } } } }
    }
}

@Composable
fun CategoryBar(name: String, value: Double, max: Double) {
    var target by remember { mutableStateOf(0f) }
    val progress by animateFloatAsState(targetValue = target, animationSpec = tween(900), label = "bar")
    LaunchedEffect(max) { target = (value / max).toFloat() }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name, fontWeight = FontWeight.Bold); Text("%,.0f ₽".format(value), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
        Box(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) { Box(modifier = Modifier.fillMaxWidth(progress).height(10.dp).clip(RoundedCornerShape(5.dp)).background(categoryColors[name] ?: MaterialTheme.colorScheme.primary)) }
    }
}

@Composable
fun PaymentsScreen(payments: List<Payment>, onAdd: (String, Double, String) -> Unit) {
    var category by remember { mutableStateOf("Электричество") }
    var amount by remember { mutableStateOf("") }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Платежи", fontSize = 28.sp, fontWeight = FontWeight.Black) }
        item { Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) { Column(modifier = Modifier.padding(16.dp)) { OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Услуга") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Сумма, ₽") }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)); Button(onClick = { val value = amount.toDoubleOrNull(); if (value != null && value > 0) { val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time); onAdd(category, value, date); amount = "" } }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Добавить") } } } }
        items(payments.reversed()) { payment -> PaymentCard(payment) }
    }
}

@Composable
fun SettingsScreen(themeIndex: Int, logoIndex: Int, apiUrl: String, apiToken: String, onTheme: (Int) -> Unit, onLogo: (Int) -> Unit, onApi: (String) -> Unit, onToken: (String) -> Unit, onSave: () -> Unit, onShare: () -> Unit, onReset: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Настройки", fontSize = 28.sp, fontWeight = FontWeight.Black) }
        item { Text("Тема", fontWeight = FontWeight.Bold) }
        item { Button(onClick = { onTheme(if (themes[themeIndex].dark) 0 else 5) }) { Text(if (themes[themeIndex].dark) "Переключить на день" else "Переключить на ночь") } }
        item { Text("Премиальные темы", fontWeight = FontWeight.Bold) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { themes.forEachIndexed { index, appTheme -> Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(appTheme.primary).clickable { onTheme(index) }) } } }
        item { Text("Логотип", fontWeight = FontWeight.Bold) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { logos.forEachIndexed { index, logo -> Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onLogo(index) }, contentAlignment = Alignment.Center) { Text(logo, fontSize = 20.sp) } } } }
        item { Text("Подключение API", fontWeight = FontWeight.Bold) }
        item { Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(modifier = Modifier.padding(16.dp)) { OutlinedTextField(value = apiUrl, onValueChange = onApi, label = { Text("URL API") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(value = apiToken, onValueChange = onToken, label = { Text("Bearer-токен") }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)); Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Сохранить") } } } }
        item { Button(onClick = onShare, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Отправить годовой отчёт") } }
        item { OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Очистить данные") } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf("Госуслуги Дом" to "https://dom.gosuslugi.ru/", "ЕПД Москва" to "https://www.mos.ru/pgu2/landing/172687/").forEach { (name, url) -> OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.weight(1f)) { Text(name) } } } }
    }
}

@Composable
fun SourcesScreen(onBack: () -> Unit, onGosuslugi: () -> Unit, onEpd: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Источники данных", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Выберите, откуда приложение должно получать информацию по квартире.", color = Color(0xFF94A3B8))
        SourceCard("Госуслуги Дом", "Собственность, лицевые счета, квитанции, оплаты, счётчики", onGosuslugi)
        SourceCard("ЕПД Москва", "Единый платёжный документ по коду плательщика", onEpd)
        SourceCard("API УК / ТСЖ", "Подключается в разделе «Ещё» по URL и токену", onBack)
        SourceCard("Интернет-провайдер", "Тариф, баланс и платежи через личный кабинет провайдера", onBack)
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
    }
}

@Composable
fun SourceCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) { Column(modifier = Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text(subtitle, fontSize = 13.sp, color = Color(0xFF94A3B8), modifier = Modifier.padding(top = 4.dp)) } }
}

fun demoPayments(): List<Payment> {
    val year = Calendar.getInstance().get(Calendar.YEAR)
    return listOf("Электричество" to 2450.0, "Газ" to 890.0, "Холодная вода" to 620.0, "Горячая вода" to 1780.0, "Отопление" to 3100.0, "Содержание жилья" to 4200.0, "Интернет" to 750.0).flatMap { (category, base) -> (1..12).map { month -> Payment(category, base * (0.85 + (month % 4) * 0.1), "$year-%02d-15".format(month)) } }
}

fun loadPayments(prefs: android.content.SharedPreferences): List<Payment> {
    return try {
        val array = JSONArray(prefs.getString("payments", "[]"))
        (0 until array.length()).map { index -> val item = array.getJSONObject(index); Payment(item.getString("category"), item.getDouble("amount"), item.getString("date"), item.optBoolean("paid", true)) }
    } catch (e: Exception) { emptyList() }
}

fun savePayments(prefs: android.content.SharedPreferences, payments: List<Payment>) {
    val array = JSONArray()
    payments.forEach { payment -> array.put(JSONObject().put("category", payment.category).put("amount", payment.amount).put("date", payment.date).put("paid", payment.paid)) }
    prefs.edit().putString("payments", array.toString()).apply()
}

suspend fun searchAddress(query: String): List<AddressResult> = withContext(Dispatchers.IO) {
    if (query.isBlank()) return@withContext emptyList()
    try {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=5&addressdetails=1&q=$encoded"
        val request = Request.Builder().url(url).header("User-Agent", "ZhKHPlus/1.0").build()
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        client.newCall(request).execute().use { response ->
            val array = JSONArray(response.body?.string() ?: "[]")
            (0 until array.length()).map { index -> val item = array.getJSONObject(index); AddressResult(item.optString("display_name"), item.optString("type", "адрес")) }
        }
    } catch (e: Exception) { listOf(AddressResult("Не удалось найти адрес", e.message ?: "ошибка сети")) }
}

suspend fun syncApi(url: String, token: String, onUpdate: (List<Payment>) -> Unit): String = withContext(Dispatchers.IO) {
    if (url.isBlank()) return@withContext "Укажите URL API в настройках"
    try {
        val request = Request.Builder().url(url).apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }.build()
        OkHttpClient().newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) return@withContext "Ошибка: HTTP ${response.code}"
            val array = if (body.trimStart().startsWith("[")) JSONArray(body) else { val obj = JSONObject(body); obj.optJSONArray("expenses") ?: obj.optJSONArray("payments") ?: obj.optJSONArray("items") ?: JSONArray() }
            val result = (0 until array.length()).mapNotNull { index -> val item = array.optJSONObject(index) ?: return@mapNotNull null; val amount = if (item.has("amount")) item.optDouble("amount") else item.optDouble("sum", 0.0); if (amount == 0.0) return@mapNotNull null; Payment(item.optString("category", item.optString("service", "Услуга")), amount, item.optString("date", item.optString("period", ""))) }
            onUpdate(result)
            "Синхронизировано записей: ${result.size}"
        }
    } catch (e: Exception) { "Ошибка синхронизации: ${e.message}" }
}

fun shareReport(context: Context, year: Int, payments: List<Payment>) {
    val data = payments.filter { it.date.startsWith(year.toString()) && it.paid }.groupBy { it.category }.mapValues { entry -> entry.value.sumOf { it.amount } }
    val text = "ЖКХ Плюс — отчёт за $year\n\n" + data.entries.sortedByDescending { it.value }.joinToString("\n") { "${it.key}: ${"%,.2f".format(it.value)} ₽" } + "\n\nВсего: ${"%,.2f".format(data.values.sum())} ₽"
    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(intent, "Отправить отчёт"))
}