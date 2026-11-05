package ru.zhkh.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Composable
fun AddressSearch(context: Context, onSelected: (String) -> Unit) {
    val prefs = remember { context.getSharedPreferences("zhkh", Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var selectedHouse by remember { mutableStateOf("") }
    var flatInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        val normalized = normalizeQuery(query)
        val flat = extractFlatNumber(normalized)
        val houseQuery = removeFlatFromQuery(normalized)

        if (houseQuery.length < 6) {
            results = emptyList()
            status = ""
            return@LaunchedEffect
        }

        busy = true
        status = "Ищем дом…"
        try {
            results = withContext(Dispatchers.IO) {
                val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=ru&addressdetails=1&limit=8&q=" +
                        URLEncoder.encode(houseQuery, "UTF-8")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "ZhkhPlus/4.3 (Android)")
                    .header("Accept-Language", "ru")
                    .build()
                OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .callTimeout(15, TimeUnit.SECONDS)
                    .build()
                    .newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IllegalStateException("Сервис адресов ответил кодом ${response.code}")
                        val array = JSONArray(body)
                        (0 until array.length()).map { array.getJSONObject(it) }
                            .distinctBy { it.optString("osm_type") + it.optString("osm_id") }
                    }
            }
            status = if (results.isEmpty()) "Ничего не найдено." else "Выберите дом."
        } catch (e: Exception) {
            results = emptyList()
            status = "Ошибка поиска: ${e.message ?: "нет соединения"}"
        } finally {
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Найдите квартиру")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Новочеркасский проспект 33 к2 кв 45") },
            supportingText = { Text("Можно писать: «к2», «кв. 45», «квартира 45»") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { item ->
                val house = item.optString("display_name")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(house)
                        val flatFromQuery = extractFlatNumber(normalizeQuery(query))
                        if (flatFromQuery.isNotBlank()) {
                            Text("Квартира: $flatFromQuery")
                        } else {
                            OutlinedTextField(
                                value = flatInput,
                                onValueChange = { flatInput = it },
                                label = { Text("Квартира: 45 или кв. 45") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                        }
                        Button(
                            onClick = {
                                val flat = flatFromQuery.ifBlank { extractFlatNumber(flatInput) }
                                if (flat.isBlank()) {
                                    status = "Укажите квартиру"
                                } else {
                                    val full = "$house, кв. $flat"
                                    prefs.edit()
                                        .putString("address", full)
                                        .putString("apartment_number", flat)
                                        .putString("apartments_list", (prefs.getString("apartments_list", "") ?: "") + full + "\n")
                                        .apply()
                                    onSelected(full)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text("Открыть квартиру") }
                    }
                }
            }
        }

        if (status.isNotBlank()) Text(status)
    }
}

fun normalizeQuery(input: String): String {
    var text = input.trim().replace(Regex("\\s+"), " ")
    text = text.replace(Regex("(?i)\\bк\\s*(\\d+)\\b"), "корпус $1")
    text = text.replace(Regex("(?i)\\bкорп\\s*(\\d+)\\b"), "корпус $1")
    text = text.replace(Regex("(?i)\\bкв\\s*\\.?\\s*(\\d+)\\b"), "квартира $1")
    return text
}

fun extractFlatNumber(input: String): String {
    val match = Regex("(?i)(?:кв|квартира)\\s*\\.?\\s*(\\d+)").find(input)
    return match?.groupValues?.get(1) ?: ""
}

fun removeFlatFromQuery(input: String): String {
    return input.replace(Regex("(?i),?\\s*(?:кв|квартира)\\s*\\.?\\s*\\d+.*$"), "").trim()
}
