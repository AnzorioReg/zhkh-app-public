package ru.zhkh.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class AddressSuggestion(
    val houseAddress: String,
    val flat: String,
    val fullAddress: String
)

private val houseCache = ConcurrentHashMap<String, List<String>>()

@Composable
fun AddressSearch(context: Context, onSelected: (String) -> Unit) {
    val prefs = remember { context.getSharedPreferences("zhkh", Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var selectedHouse by remember { mutableStateOf("") }
    var flatInput by remember { mutableStateOf("") }
    var dadataKey by remember { mutableStateOf(prefs.getString("dadata_key", "") ?: "") }

    LaunchedEffect(query, dadataKey) {
        val text = normalizeQuery(query)
        val flatInQuery = extractFlatNumber(text)
        val houseQuery = removeFlatFromQuery(text)

        if (houseQuery.length < 6) {
            results = emptyList()
            status = ""
            return@LaunchedEffect
        }

        val cached = houseCache[houseQuery]
        if (cached != null) {
            results = cached.map { house ->
                AddressSuggestion(house, flatInQuery, if (flatInQuery.isBlank()) house else "$house, кв. $flatInQuery")
            }
            status = if (cached.isEmpty()) "Ничего не найдено." else "Выберите дом."
            return@LaunchedEffect
        }

        busy = true
        status = "Ищем дом…"
        try {
            val houses = withContext(Dispatchers.IO) {
                if (dadataKey.isNotBlank()) dadataHouses(houseQuery, dadataKey)
                else nominatimHouses(houseQuery)
            }
            houseCache[houseQuery] = houses
            results = houses.map { house ->
                AddressSuggestion(house, flatInQuery, if (flatInQuery.isBlank()) house else "$house, кв. $flatInQuery")
            }
            status = when {
                houses.isEmpty() -> "Дом не найден. Уточните улицу или город."
                flatInQuery.isBlank() -> "Выберите дом, затем укажите квартиру."
                else -> "Дом найден. Квартира $flatInQuery будет сохранена с адресом."
            }
        } catch (e: Exception) {
            results = emptyList()
            status = "Ошибка поиска: ${e.message ?: "нет соединения"}"
        } finally {
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B1220)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("ЖКХ Плюс", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Введите адрес до квартиры", color = Color(0xFF9FB3C8))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Новочеркасский проспект 33 к2 кв 45") },
            supportingText = { Text("Можно писать: «к2», «кв. 45», «квартира 45»") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(results) { item ->
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16233B))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(item.houseAddress, color = Color.White, fontWeight = FontWeight.SemiBold)
                        if (item.flat.isNotBlank()) {
                            Text("Квартира: ${item.flat}", color = Color(0xFF00D1B2))
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
                                val flat = item.flat.ifBlank { extractFlatNumber(flatInput) }
                                if (flat.isBlank()) {
                                    status = "Укажите квартиру"
                                } else {
                                    val full = "${item.houseAddress}, кв. $flat"
                                    prefs.edit()
                                        .putString("address", full)
                                        .putString("apartment_number", flat)
                                        .putString("apartments_list", (prefs.getString("apartments_list", "") ?: "") + full + "\n")
                                        .apply()
                                    onSelected(full)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        ) { Text("Открыть квартиру") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF16233B))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Точный поиск до квартиры", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Для быстрого поиска по всей России вставьте бесплатный API-ключ DaData.", color = Color(0xFF9FB3C8))
                OutlinedTextField(
                    value = dadataKey,
                    onValueChange = {
                        dadataKey = it
                        prefs.edit().putString("dadata_key", it).apply()
                    },
                    label = { Text("API-ключ DaData") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (status.isNotBlank()) Text(status, color = Color(0xFF9FB3C8))
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
    return match?.groupValues?.get(1).orEmpty()
}

fun removeFlatFromQuery(input: String): String {
    return input.replace(Regex("(?i),?\\s*(?:кв|квартира)\\s*\\.?\\s*\\d+.*$"), "").trim()
}

private val http = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(12, TimeUnit.SECONDS)
    .build()

private fun dadataHouses(query: String, apiKey: String): List<String> {
    val request = Request.Builder()
        .url("https://suggestions.dadata.ru/suggestions/api/4_1/rs/suggest/address")
        .header("Authorization", "Token $apiKey")
        .header("Content-Type", "application/json")
        .post(
            JSONObject().put("query", query).put("count", 10).toString()
                .toRequestBody("application/json".toMediaType())
        )
        .build()

    http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (response.code == 401 || response.code == 403) throw IllegalStateException("Неверный ключ DaData")
        if (response.code == 429) throw IllegalStateException("Лимит DaData исчерпан")
        if (!response.isSuccessful) throw IllegalStateException("DaData: ${response.code}")
        val suggestions = JSONObject(body).optJSONArray("suggestions") ?: JSONArray()
        return (0 until suggestions.length())
            .map { suggestions.getJSONObject(i).optString("value") }
            .filter { it.contains(Regex("\\d")) }
            .distinct()
    }
}

private fun nominatimHouses(query: String): List<String> {
    val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=10&q=" +
            URLEncoder.encode(query, "UTF-8")
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "ZhkhPlus/4.2 (Android)")
        .header("Accept-Language", "ru")
        .build()

    http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (response.code == 429) throw IllegalStateException("OpenStreetMap ограничил запросы")
        if (!response.isSuccessful) throw IllegalStateException("OpenStreetMap: ${response.code}")
        val array = JSONArray(body)
        return (0 until array.length())
            .map { array.getJSONObject(i).optString("display_name") }
            .filter { it.contains(Regex("\\d")) }
            .distinct()
    }
}
