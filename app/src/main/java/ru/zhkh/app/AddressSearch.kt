package ru.zhkh.app

import android.content.Context
import androidx.compose.foundation.layout.*
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
 var house by remember { mutableStateOf(prefs.getString("selected_house", "") ?: "") }
 var flat by remember { mutableStateOf(prefs.getString("apartment_number", "") ?: "") }
 var account by remember { mutableStateOf(prefs.getString("apartment_account", "") ?: "") }
 var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
 var status by remember { mutableStateOf("") }
 var busy by remember { mutableStateOf(false) }
 val scope = rememberCoroutineScope()
 Card(Modifier.fillMaxWidth()) {
  Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
   Text("Дом и квартира", style = MaterialTheme.typography.titleLarge)
   Text("Сначала найдите дом. Квартиру укажите отдельно: карта не подтверждает существование помещения или право собственности.")
   OutlinedTextField(query, { query = it }, label = { Text("Улица, дом, корпус; город при необходимости") }, modifier = Modifier.fillMaxWidth())
   Button(enabled = !busy && query.trim().length >= 4, onClick = {
    busy = true
    results = emptyList()
    val buildingQuery = query.replace(Regex("(?i)(?:,?\\s*)(?:кв\\.?|квартира)\\s*[^,]*$"), "").trim()
    scope.launch {
     try {
      results = withContext(Dispatchers.IO) {
       val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=ru&addressdetails=1&limit=8&q=" + URLEncoder.encode(buildingQuery, "UTF-8")
       val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
       val request = Request.Builder().url(url).header("User-Agent", "ZhkhPlus/2.2 (https://github.com/AnzorioReg/zhkh-app-public)").header("Accept-Language", "ru").build()
       client.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "HTTP ${response.code}" }
        val array = JSONArray(response.body?.string() ?: "[]")
        (0 until array.length()).map { array.getJSONObject(it) }.distinctBy { it.optString("osm_type") + it.optString("osm_id") }
       }
      }
      status = if(results.isEmpty()) "Дом не найден в этом источнике. Уточните город и название улицы." else "Выберите дом и проверьте город, номер и корпус. Результат может быть улицей, если дом не найден."
     } catch(e: Exception) { status = "Ошибка поиска: ${e.message}" }
     finally { busy = false }
    }
   }) { Text(if(busy) "Поиск…" else "Найти дом без номера квартиры") }
   results.forEach { item ->
    val a = item.optJSONObject("address") ?: JSONObject()
    val city = a.optString("city").ifBlank { a.optString("town").ifBlank { a.optString("village").ifBlank { a.optString("state") } } }
    val number = a.optString("house_number")
    OutlinedButton(enabled = number.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
     house = item.optString("display_name")
     prefs.edit().putString("selected_house",house).putString("house_source_id",item.optString("osm_type") + ":" + item.optString("osm_id")).apply()
     results = emptyList()
     status = "Дом выбран. Укажите квартиру и сохраните полный адрес."
    }) { Column { Text(city); Text(item.optString("display_name")); if(number.isBlank()) Text("Номер дома не определён — уточните запрос") } }
   }
   if(house.isNotBlank()) Text("Выбранный дом: $house")
   OutlinedTextField(flat, { flat = it }, label = { Text("Квартира / помещение") }, modifier = Modifier.fillMaxWidth())
   OutlinedTextField(account, { account = it }, label = { Text("Лицевой счёт из квитанции (необязательно)") }, modifier = Modifier.fillMaxWidth())
   Button(enabled = house.isNotBlank() && flat.isNotBlank(), onClick = {
    val full = "$house, кв. ${flat.trim()}"
    prefs.edit().putString("address",full).putString("apartment_number",flat.trim()).putString("apartment_account",account.trim()).apply()
    onSelected(full)
    status = "Полный адрес сохранён. Квартира и счёт введены вами, в реестре не проверены. Доступ к квитанциям и задолженности не подключён."
   }) { Text("Сохранить дом и квартиру") }
   Text(status)
   Text("Источник поиска дома: OpenStreetMap, ODbL. Данные могут быть неполными. Сохранение квартиры не является подключением ГИС ЖКХ.", style = MaterialTheme.typography.bodySmall)
  }
 }
}
