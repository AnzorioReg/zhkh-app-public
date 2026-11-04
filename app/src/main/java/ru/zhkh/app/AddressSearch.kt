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

data class AddressSuggestion(val title: String, val subtitle: String)

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
        val text = query.trim()
        if (text.length < 4) {
            results = emptyList()
            status = ""
            return@LaunchedEffect
        }
        busy = true
        status = "Ищем адреса…"
        try {
            val loaded = withContext(Dispatchers.IO) { searchAddresses(text) }
            results = loaded
            status = when {
                loaded.isEmpty() -> "Ничего не найдено. Попробуйте добавить город: например, «Новочеркасский проспект 33 Санкт-Петербург»."
                else -> "Выберите адрес из списка."
            }
        } catch (e: Exception) {
            results = emptyList()
            status = "Не удалось найти адрес: ${e.message ?: "нет соединения"}."
        } finally {
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Найдите квартиру", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Начните вводить адрес") },
            supportingText = { Text("Улица, дом и город работают лучше всего") },
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
                            if (item.subtitle.isNotBlank()) {
                                Text(item.subtitle, style = MaterialTheme.typography.bodySmall)
                            }
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
                    OutlinedTextField(flat, { flat = it }, label = { Text("Квартира") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(account, { account = it }, label = { Text("Лицевой счёт") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        enabled = flat.isNotBlank(),
                        onClick = {
                            val full = "$selectedHouse, кв. ${flat.trim()}"
                            prefs.edit()
                                .putString("address", full)
                                .putString("apartment_number", flat.trim())
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

private fun searchAddresses(query: String): List<AddressSuggestion> {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    return try {
        photonSearch(client, query)
    } catch (first: Exception) {
        try {
            nominatimSearch(client, query)
        } catch (second: Exception) {
            throw IllegalStateException("Проверьте интернет. ${second.message ?: ""}".trim())
        }
    }
}

private fun photonSearch(client: OkHttpClient, query: String): List<AddressSuggestion> {
    val url = "https://photon.komoot.io/api/?lang=ru&limit=10&q=" + URLEncoder.encode(query, "UTF-8")
    val response = client.newCall(request(url)).execute()
    response.use {
        val body = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw IllegalStateException("Адресный сервис ответил кодом ${it.code}")
        val features = JSONObject(body).optJSONArray("features") ?: JSONArray()
        return (0 until features.length()).mapNotNull { i ->
            val feature = features.getJSONObject(i)
            val properties = feature.optJSONObject("properties") ?: return@mapNotNull null
            val street = listOfNotNull(
                properties.optString("name").ifBlank { null },
                properties.optString("housenumber").ifBlank { null }
            ).joinToString(" ")
            val city = properties.optString("city").ifBlank {
                properties.optString("town").ifBlank { properties.optString("county") }
            }
            val title = listOfNotNull(street.ifBlank { null }, city.ifBlank { null }).joinToString(", ")
                .ifBlank { properties.optString("label") }
            if (title.isBlank()) null else AddressSuggestion(title, properties.optString("label"))
        }.distinctBy { it.title }
    }
}

private fun nominatimSearch(client: OkHttpClient, query: String): List<AddressSuggestion> {
    val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=10&q=" +
            URLEncoder.encode(query, "UTF-8")
    val response = client.newCall(request(url)).execute()
    response.use {
        val body = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw IllegalStateException("Адресный сервис ответил кодом ${it.code}")
        val array = JSONArray(body)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            AddressSuggestion(item.optString("display_name"), "")
        }.distinctBy { it.title }
    }
}

private fun request(url: String): Request =
    Request.Builder()
        .url(url)
        .header("User-Agent", "ZhkhPlus/3.2 (Android; contact: user)")
        .header("Accept", "application/json")
        .header("Accept-Language", "ru")
        .build()
