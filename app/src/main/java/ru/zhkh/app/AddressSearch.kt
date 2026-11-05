package ru.zhkh.app

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private object HouseLookup {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val cache = LinkedHashMap<String, String>()
    private var nextAllowed = 0L
    @Synchronized fun search(query: String): String {
        cache[query]?.let { return it }
        val remaining = nextAllowed - SystemClock.elapsedRealtime()
        check(remaining <= 0) { "Повторный поиск доступен через ${(remaining + 999) / 1000} с" }
        nextAllowed = SystemClock.elapsedRealtime() + 1500
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=ru&addressdetails=1&limit=10&q=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder().url(url)
            .header("User-Agent", "ZhkhPlus/4.5 (https://github.com/AnzorioReg/zhkh-app-public)")
            .header("Accept-Language", "ru").build()
        client.newCall(req).execute().use { response ->
            if (response.code == 429) {
                val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60L, 86400L) ?: 60L
                nextAllowed = SystemClock.elapsedRealtime() + seconds * 1000
                error("Сервис ограничил запросы. Пауза $seconds с; автоматических повторов нет")
            }
            check(response.isSuccessful) { "Сервис адресов: HTTP ${response.code}" }
            val body = response.body?.string() ?: error("Пустой ответ сервера")
            JSONArray(body)
            if (cache.size >= 100) cache.remove(cache.keys.first())
            cache[query] = body
            return body
        }
    }
}

@Composable
fun AddressSearch(context: Context, onSelected: (String) -> Unit) {
    val prefs = remember { context.getSharedPreferences("zhkh", Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf("") }
    var flatInput by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var searchedHouse by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val houseQuery = removeFlatFromQuery(normalizeQuery(query))
    val flatFromQuery = extractFlatNumber(normalizeQuery(query))
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Моя квартира", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(query, { query = it }, label = { Text("Улица, дом, корпус, квартира") }, supportingText = { Text("Например: Новочеркасский проспект 33 к2 кв 45") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && houseQuery.length >= 4, onClick = {
            val submitted = houseQuery
            busy = true
            status = "Ищем дом…"
            scope.launch {
                try {
                    val body = withContext(Dispatchers.IO) { HouseLookup.search(submitted) }
                    val array = JSONArray(body)
                    results = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    searchedHouse = submitted
                    status = if (results.isEmpty()) "Дом не найден. Уточните адрес." else "Выберите дом. Номер квартиры указан вами и не проверен реестром."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { results = emptyList(); status = e.message ?: "Ошибка соединения" }
                finally { busy = false }
            }
        }) { Text(if (busy) "Поиск…" else "Найти дом") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (searchedHouse == houseQuery) items(results) { item ->
                val house = item.optString("display_name")
                val hasNumber = !item.optJSONObject("address")?.optString("house_number").isNullOrBlank()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(house)
                        if (!hasNumber) Text("Номер дома не определён. Уточните запрос.")
                        if (flatFromQuery.isNotBlank()) Text("Квартира $flatFromQuery — из вашего ввода")
                        else OutlinedTextField(flatInput, { flatInput = it }, label = { Text("Номер квартиры") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = hasNumber, onClick = {
                            val flat = flatFromQuery.ifBlank { extractFlatNumber(flatInput) }
                            if (flat.isBlank()) status = "Укажите квартиру в общей строке или в поле номера"
                            else {
                                val full = "$house, кв. $flat"
                                val saved = (prefs.getString("apartments_list", "") ?: "").lines().filter { it.isNotBlank() }.toMutableSet()
                                saved.add(full)
                                prefs.edit().putString("address", full).putString("apartment_number", flat).putString("apartments_list", saved.joinToString("\n")).apply()
                                onSelected(full)
                            }
                        }) { Text("Сохранить и открыть квартиру") }
                    }
                }
            }
        }
        Text("Поиск дома: © OpenStreetMap contributors, ODbL. Квартира не подтверждена. Поиск выполняется только по кнопке.", style = MaterialTheme.typography.bodySmall)
    }
}

fun normalizeQuery(input: String): String {
    return input.trim().replace(Regex("\\s+"), " ")
        .replace(Regex("(?i)(?<![\\p{L}])(?:корп\\.?|к)\\s*(\\d+)"), "корпус $1")
}
fun extractFlatNumber(input: String): String {
    val match = Regex("(?i)(?<![\\p{L}])(?:квартира|кв\\.?)\\s*([0-9]+[\\p{L}]?(?:[-/][0-9]+)?)").find(input)
    return match?.groupValues?.get(1) ?: input.trim().takeIf { it.matches(Regex("[0-9]+[\\p{L}]?(?:[-/][0-9]+)?")) }.orEmpty()
}
fun removeFlatFromQuery(input: String): String {
    return input.replace(Regex("(?i)[,\\s]*(?<![\\p{L}])(?:квартира|кв\\.?)(?:\\s.*|[0-9].*)?$"), "").trim()
}
