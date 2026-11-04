package ru.zhkh.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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

data class AddressSuggestion(val fullAddress: String, val house: String, val flat: String)

private val cache = ConcurrentHashMap<String, List<AddressSuggestion>>()

@Composable
fun AddressSearch(context: Context, onSelected: (String) -> Unit) {
    val prefs = remember { context.getSharedPreferences("zhkh", Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dadataKey by remember { mutableStateOf(prefs.getString("dadata_key", "") ?: "") }

    LaunchedEffect(query, dadataKey) {
        val text = normalizeQuery(query)
        if (text.length < 8) {
            results = emptyList()
            status = ""
            return@LaunchedEffect
        }

        val key = "$dadataKey|$text"
        cache[key]?.let {
            results = it
            status = if (it.isEmpty()) "Ничего не найдено." else "Выберите квартиру"
            return@LaunchedEffect
        }

        busy = true
        status = "Ищем квартиру…"
        try {
            val loaded = withContext(Dispatchers.IO) {
                if (dadataKey.isNotBlank()) dadataSearch(text, dadataKey)
                else nominatimSearch(text)
            }
            cache[key] = loaded
            results = loaded
            status = if (loaded.isEmpty()) "Не найдено. Уточните дом или квартиру." else "Выберите квартиру"
        } catch (e: Exception) {
            results = emptyList()
            status = "Ошибка: ${e.message ?: "нет соединения"}"
        } finally {
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B1220)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("ЖКХ Плюс", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Найдите квартиру полностью", color = Color(0xFF9FB3C8))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Новочеркасский проспект 33 к2 кв 45") },
            supportingText = { Text("Можно писать: к2, кв. 45, квартира 45") },
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
                        Text(item.house, color = Color.White, fontWeight = FontWeight.SemiBold)
                        if (item.flat.isNotBlank()) {
                            Text("Квартира: ${item.flat}", color = Color(0xFF00D1B2))
                        }
                        Text(item.fullAddress, color = Color(0xFF9FB3C8), fontSize = 12.sp)
                        Button(
                            onClick = {
                                prefs.edit()
                                    .putString("address", item.fullAddress)
                                    .putString("apartment_number", item.flat)
                                    .putString("apartments_list", (prefs.getString("apartments_list", "") ?: "") + item.fullAddress + "\n")
                                    .apply()
                                onSelected(item.fullAddress)
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        ) { Text("Открыть квартиру") }
                    }
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16233B))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Быстрый поиск", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Для поиска до квартиры вставьте бесплатный API-ключ DaData.", color = Color(0xFF9FB3C8))
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

private fun dadataSearch(query: String, apiKey: String): List<AddressSuggestion> {
    val request = Request.Builder()
        .url("https://suggestions.dadata.ru/suggestions/api/4_1/rs/suggest/address")
        .header("Authorization", "Token $apiKey")
        .header("Content-Type", "application/json")
        .post(JSONObject().put("query", query).put("count", 10).toString()
            .toRequestBody("application/json".toMediaType()))
        .build()

    http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (response.code == 401 || response.code == 403) throw IllegalStateException("Неверный ключ DaData")
        if (response.code == 429) throw IllegalStateException("Лимит DaData исчерпан")
        if (!response.isSuccessful) throw IllegalStateException("DaData: ${response.code}")
        val suggestions = JSONObject(body).optJSONArray("suggestions") ?: JSONArray()
        return (0 until suggestions.length()).map { i ->
            val item = suggestions.getJSONObject(i)
            val value = item.optString("value")
            val data = item.optJSONObject("data") ?: JSONObject()
            AddressSuggestion(value, value, data.optString("flat"))
        }.distinctBy { it.fullAddress }
    }
}

private fun nominatimSearch(query: String): List<AddressSuggestion> {
    val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=10&q=" +
            URLEncoder.encode(query, "UTF-8")
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "ZhkhPlus/4.0 (Android)")
        .header("Accept-Language", "ru")
        .build()

    http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (response.code == 429) throw IllegalStateException("OpenStreetMap ограничил запросы")
        if (!response.isSuccessful) throw IllegalStateException("OpenStreetMap: ${response.code}")
        val array = JSONArray(body)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val address = item.optJSONObject("address") ?: JSONObject()
            AddressSuggestion(item.optString("display_name"), item.optString("display_name"), address.optString("apartment"))
        }.distinctBy { it.fullAddress }
    }
}

private val http = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(12, TimeUnit.SECONDS)
    .build()
