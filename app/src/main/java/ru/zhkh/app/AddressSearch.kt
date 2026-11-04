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
    var results by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var selectedHouse by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        val original = query.trim()
        val text = normalizeAddressQuery(original)
        if (text.length < 6) {
            results = emptyList()
            status = ""
            return@LaunchedEffect
        }

        addressCache[text]?.let {
            results = it
            status = if (it.isEmpty()) "Ничего не найдено." else "Выберите адрес."
            return@LaunchedEffect
        }

        busy = true
        status = "Ищем…"
        try {
            val loaded = withContext(Dispatchers.IO) { searchAddresses(text) }
            addressCache[text] = loaded
            results = loaded
            status = if (loaded.isEmpty()) "Ничего не найдено. Попробуйте добавить номер дома." else "Выберите адрес."
        } catch (e: Exception) {
            results = emptyList()
            status = "Ошибка поиска: ${e.message ?: "нет соединения"}. Попробуйте через минуту."
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

private fun searchAddresses(query: String): List<AddressSuggestion> {
    val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    return try {
        nominatimSearch(client, query)
    } catch (first: Exception) {
        try {
            photonSearch(client, query)
        } catch (second: Exception) {
            throw IllegalStateException("Адресные сервисы временно ограничили запросы. ${second.message ?: ""}".trim())
        }
    }
}

private fun nominatimSearch(client: OkHttpClient, query: String): List<AddressSuggestion> {
    val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=10&q=" +
            URLEncoder.encode(query, "UTF-8")
    val response = client.newCall(request(url)).execute()
    response.use {
        val body = it.body?.string().orEmpty()
        if (it.code == 429) throw IllegalStateException("Сервис ограничил частоту запросов")
        if (!it.isSuccessful) throw IllegalStateException("Адресный сервис ответил кодом ${it.code}")
        val array = JSONArray(body)
        return (0 until array.length()).map { i ->
            AddressSuggestion(array.getJSONObject(i).optString("display_name"), "")
        }.filter { it.title.contains(Regex("\\d")) }
            .distinctBy { it.title }
    }
}

private fun photonSearch(client: OkHttpClient, query: String): List<AddressSuggestion> {
    val url = "https://photon.komoot.io/api/?lang=ru&limit=10&q=" + URLEncoder.encode(query, "UTF-8")
    val response = client.newCall(request(url)).execute()
    response.use {
        val body = it.body?.string().orEmpty()
        if (it.code == 429) throw IllegalStateException("Сервис ограничил частоту запросов")
        if (!it.isSuccessful) throw IllegalStateException("Адресный сервис ответил кодом ${it.code}")
        val features = JSONObject(body).optJSONArray("features") ?: JSONArray()
        return (0 until features.length()).mapNotNull { i ->
            val feature = features.getJSONObject(i)
            val properties = feature.optJSONObject("properties") ?: return@mapNotNull null
            val street = properties.optString("name")
            val house = properties.optString("housenumber")
            val city = properties.optString("city").ifBlank {
                properties.optString("town").ifBlank { properties.optString("county") }
            }
            val title = listOfNotNull(street.ifBlank { null }, house.ifBlank { null }, city.ifBlank { null })
                .joinToString(" ")
                .ifBlank { properties.optString("label") }
            if (title.isBlank()) null else AddressSuggestion(title, properties.optString("label"))
        }.filter { it.title.contains(Regex("\\d")) }
            .distinctBy { it.title }
    }
}

private fun request(url: String): Request =
    Request.Builder()
        .url(url)
        .header("User-Agent", "ZhkhPlus/3.4 (Android; https://github.com/AnzorioReg/zhkh-app-public)")
        .header("Accept", "application/json")
        .header("Accept-Language", "ru")
        .build()
