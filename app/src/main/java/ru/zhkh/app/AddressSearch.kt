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
    var flat by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
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
            val loaded = withContext(Dispatchers.IO) {
                val url = "https://nominatim.openstreetmap.org/search" +
                        "?format=jsonv2&countrycodes=ru&addressdetails=1&limit=8&q=" +
                        URLEncoder.encode(text, "UTF-8")
                val client = OkHttpClient.Builder()
                    .callTimeout(20, TimeUnit.SECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "ZhkhPlus/3.1 (Android)")
                    .header("Accept-Language", "ru")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Сервер адресов ответил кодом ${response.code}")
                    }
                    val array = JSONArray(body)
                    (0 until array.length()).map { array.getJSONObject(it) }
                        .distinctBy { it.optString("osm_type") + it.optString("osm_id") }
                }
            }
            results = loaded
            status = if (loaded.isEmpty()) "Ничего не найдено. Уточните город, улицу или дом." else "Выберите адрес из списка."
        } catch (e: Exception) {
            results = emptyList()
            status = "Не удалось найти адрес: ${e.message ?: "нет соединения"}. Проверьте интернет и попробуйте ещё раз."
        } finally {
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Найдите квартиру", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Начните вводить адрес") },
            supportingText = { Text("Например: Тверская 1 Москва") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(results) { item ->
                val address = item.optString("display_name")
                Card(Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            selectedHouse = address
                            prefs.edit().putString("selected_house", address).apply()
                            results = emptyList()
                            status = "Адрес выбран. Укажите квартиру."
                        }
                    ) {
                        Text(address)
                    }
                }
            }
        }

        if (selectedHouse.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Квартира", style = MaterialTheme.typography.titleMedium)
                    Text(selectedHouse)
                    OutlinedTextField(
                        value = flat,
                        onValueChange = { flat = it },
                        label = { Text("Квартира") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it },
                        label = { Text("Лицевой счёт") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = flat.isNotBlank(),
                        onClick = {
                            val full = "$selectedHouse, кв. ${flat.trim()}"
                            prefs.edit()
                                .putString("address", full)
                                .putString("apartment_number", flat.trim())
                                .putString("apartment_account", account.trim())
                                .putString(
                                    "apartments_list",
                                    (prefs.getString("apartments_list", "") ?: "") + full + "\n"
                                )
                                .apply()
                            onSelected(full)
                            status = "Квартира добавлена в список."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сохранить квартиру")
                    }
                }
            }
        }

        if (status.isNotBlank()) {
            Text(status)
        }
    }
}
