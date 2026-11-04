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

data class AddressSuggestion(val title: String, val subtitle: String)

private val addressCache = ConcurrentHashMap<String, List<AddressSuggestion>>()

@Composable
fun AddressSearch(context: Context, onSelected: (String) -> Unit) {
    val prefs = remember { context.getSharedPreferences("zhkh", Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf("") }
    var flat by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var dadataKey by remember { mutableStateOf(prefs.getString("dadata_key", "") ?: "") }
    var results by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var selectedHouse by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, dadataKey) {
        val original = query.trim()
        val text = normalizeAddressQuery(original)
        if (text.length < 6) {
            results = emptyList()
            status = ""
            return@LaunchedEffect
        }

        addressCache["$dadataKey|$text"]?.let {
            results = it
            status = if (it.isEmpty()) "Ничего не найдено." else "Выберите адрес."
            return@LaunchedEffect
        }

        busy = true
        status = "Ищем…"
        try {
            val loaded = withContext(Dispatchers.IO) {
                if (dadataKey.isNotBlank()) dadataSearch(text, dadataKey)
                else nominatimSearch(text)
            }
            addressCache["$dadataKey|$text"] = loaded
            results = loaded
            status = if (loaded.isEmpty()) "Ничего не найдено. Попробуйте добавить номер дома." else "Выберите адрес."
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
            label = { Text("Новочеркасский проспект 33 к2") },
            supportingText = { Text("Можно писать: «к2», «кв. 45», «квартира 45»") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { item ->
                Card(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        selectedHouse = item.title
                        prefs.edit().putString("selected_house", item.title).apply()
                        results = emptyList()
                        status = "Адрес выбран. Укажите квартиру."
                    }) {
                        Column {
                            Text(item.title)
                            if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Быстрый поиск адресов", style = MaterialTheme.typography.titleMedium)
                Text("Для точных и быстрых подсказок по всей России вставьте бесплатный API-ключ DaData. Без ключа работает резервный поиск OpenStreetMap.")
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

        if (selectedHouse.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Квартира", style = MaterialTheme.typography.titleMedium)
                    Text(selectedHouse)
                    OutlinedTextField(flat, { flat = it }, label = { Text("Квартира: 45 или кв. 45") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(account, { account = it }, label = { Text("Лицевой счёт") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        enabled = flat.isNotBlank(),
                        onClick = {
                            val flatNumber = extractFlatNumber(flat)
                            val full = "$selectedHouse, кв. $flatNumber"
                            prefs.edit()
                                .putString("address", full)
                                .putString("apartment_number", flatNumber)
                                .putString("apartment_account", account.trim())
                                .putString("apartments_list", (prefs.getString("apartments_list", "") ?: "") + full + "\n")
                                .apply()
                            onSelected(full)
                            status = "Квартира добавлена в список."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить квартиру") }
                }
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
    return match?.groupValues?.get(1) ?: input.filter { it.isDigit() }.ifBlank { input.trim() }
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
            AddressSuggestion(item.optString("value"), item.optString("unrestricted_value"))
        }.distinctBy { it.title }
    }
}

private fun nominatimSearch(query: String): List<AddressSuggestion> {
    val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=10&q=" +
            URLEncoder.encode(query, "UTF-8")
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "ZhkhPlus/3.5 (Android; https://github.com/AnzorioReg/zhkh-app-public)")
        .header("Accept", "application/json")
        .header("Accept-Language", "ru")
        .build()
    http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (response.code == 429) throw IllegalStateException("Публичный OpenStreetMap временно ограничил запросы")
        if (!response.isSuccessful) throw IllegalStateException("OpenStreetMap ответил кодом ${response.code}")
        val array = JSONArray(body)
        return (0 until array.length()).map { i ->
            AddressSuggestion(array.getJSONObject(i).optString("display_name"), "")
        }.filter { it.title.contains(Regex("\\d")) }
            .distinctBy { it.title }
    }
}
