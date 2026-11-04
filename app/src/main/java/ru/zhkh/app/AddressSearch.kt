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
            return@LaunchedEffect
        }
        busy = true
        try {
            results = withContext(Dispatchers.IO) {
                val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=ru&addressdetails=1&limit=8&q=" +
                        URLEncoder.encode(text, "UTF-8")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "ZhkhPlus/3.0")
                    .header("Accept-Language", "ru")
                    .build()
                OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
                    .newCall(request).execute().use { response ->
                        val array = JSONArray(response.body?.string() ?: "[]")
                        (0 until array.length()).map { array.getJSONObject(it) }
                            .distinctBy { it.optString("osm_type") + it.optString("osm_id") }
                    }
            }
        } catch (e: Exception) {
            status = "Ошибка поиска: ${e.message}"
        } finally {
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Найдите квартиру", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Начните вводить адрес") },
            supportingText = { Text("Например: улица, дом, город") },
            modifier = Modifier.fillMaxWidth()
        )

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { item ->
                val address = item.optString("display_name")
                Card(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        selectedHouse = address
                        prefs.edit().putString("selected_house", address).apply()
                        results = emptyList()
                    }) { Text(address) }
                }
            }
        }

        if (selectedHouse.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Квартира", style = MaterialTheme.typography.titleMedium)
                    Text(selectedHouse)
                    OutlinedTextField(flat, { flat = it }, label = { Text("Квартира") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(account, { account = it }, label = { Text("Лицевой счёт") }, modifier = Modifier.fillMaxWidth())
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
