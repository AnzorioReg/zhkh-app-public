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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class AddressSuggestion(val title: String, val subtitle: String, val flat: String)

private val addressCache = ConcurrentHashMap<String, List<AddressSuggestion>>()

@Composable
fun AddressSearch(context: Context, onSelected: (String) -> Unit) {
    val prefs = remember { context.getSharedPreferences("zhkh", android.content.Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf("") }
    var dadataKey by remember { mutableStateOf(prefs.getString("dadata_key", "") ?: "") }
    var results by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, dadataKey) {
        val original = query.trim()
        val normalized = normalizeAddressQuery(original)
        if (normalized.length < 6) {
            results = emptyList()
            status = ""
            return@LaunchedEffect
        }

        val cacheKey = "$dadataKey|$normalized"
        addressCache[cacheKey]?.let {
            results = it
            status = if (it.isEmpty()) "Ничего не найдено." else "Выберите полный адрес с квартирой."
            return@LaunchedEffect
        }

        busy = true
        status = "Ищем…"
        try {
            val loaded = withContext(Dispatchers.IO) {
                if (dadataKey.isNotBlank()) dadataSearch(normalized, dadataKey)
                else nominatimSearch(normalized)
            }
            addressCache[cacheKey] = loaded
            results = loaded
            status = if (loaded.isEmpty()) "Ничего не найдено. Попробуйте написать улицу, дом и квартиру." else "Выберите адрес."
        } catch (e: Exception) {
            results = emptyList()
            status = "Ошибка поиска: ${e.message ?: "нет соединения"}."
        } finally {
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Найдите квартиру", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Полный адрес: улица, дом, к2, кв 45") },
            supportingText = { Text("Можно писать: «Новочеркасский проспект 33 к2 кв 45»") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { item ->
                Card(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        val fullAddress = if (item.flat.isNotBlank()) "${item.title}, кв. ${item.flat}" else item.title
                        prefs.edit()
                            .putString("address", fullAddress)
                            .putString("apartment_number", item.flat)
                            .putString("selected_house", item.title)
                            .putString("apartments_list", (prefs.getString("apartments_list", "") ?: "") + fullAddress + "\n")
                            .apply()
                        onSelected(fullAddress)
                    }) {
                        Column {
                            Text(item.title)
                            if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall)
                            if (item.flat.isNotBlank()) Text("Квартира: ${item.flat}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Быстрый поиск по России", style = MaterialTheme.typography.titleMedium)
                Text("Для точного поиска до квартиры вставьте бесплатный API-ключ DaData. Без ключа работает резервный поиск OpenStreetMap.")
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

        if (status.isNotBlank()) Text(status)
    }
}

fun normalizeAddressQuery(input: String): String {
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

private val http = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(12, TimeUnit.SECONDS)
    .build()

private fun dadataSearch(query: String, apiKey: String): List<AddressSuggestion> {
    val url = "https://suggestions.dadata.ru/suggestions/api/4_1/rs/suggest/address"
    val json = JSONObject().put("query", query).put("count", 10).toString()
    val request = Request.Builder()
        .url(url)
        .header("Authorization", "Token $apiKey")
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .post(json.toRequestBody("application/json".toMediaType()))
        .build()
    http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (response.code == 401 || response.code == 403) throw IllegalStateException("Ключ DaData неверный")
        if (response.code == 429) throw IllegalStateException("Лимит DaData на сегодня исчерпан")
        if (!response.isSuccessful) throw IllegalStateException("DaData ответил кодом ${response.code}")
        val suggestions = JSONObject(body).optJSONArray("suggestions") ?: JSONArray()
        return (0 until suggestions.length()).map { i ->
            val item = suggestions.getJSONObject(i)
            val data = item.optJSONObject("data") ?: JSONObject()
            AddressSuggestion(
                item.optString("value"),
                item.optString("unrestricted_value"),
                data.optString("flat")
            )
        }.distinctBy { it.title + it.flat }
    }
}

private fun nominatimSearch(query: String): List<AddressSuggestion> {
    val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=10&q=" +
            URLEncoder.encode(query, "UTF-8")
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "ZhkhPlus/3.6 (Android; https://github.com/AnzorioReg/zhkh-app-public)")
        .header("Accept", "application/json")
        .header("Accept-Language", "ru")
        .build()
    http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (response.code == 429) throw IllegalStateException("Публичный OpenStreetMap временно ограничил запросы")
        if (!response.isSuccessful) throw IllegalStateException("OpenStreetMap ответил кодом ${response.code}")
        val array = JSONArray(body)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val address = item.optJSONObject("address") ?: JSONObject()
            AddressSuggestion(
                item.optString("display_name"),
                "",
                address.optString("apartment")
            )
        }.distinctBy { it.title + it.flat }
    }
}
