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
 var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
 var details by remember { mutableStateOf(prefs.getString("house_details", "") ?: "") }
 var status by remember { mutableStateOf("") }
 var busy by remember { mutableStateOf(false) }
 val scope = rememberCoroutineScope()
 Card(Modifier.fillMaxWidth()) {
  Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
   Text("Найти мой дом", style = MaterialTheme.typography.titleLarge)
   Text("Укажите улицу, дом и корпус. В результатах будет город. Номер квартиры добавьте после выбора дома.")
   OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Город, улица, дом") }, modifier = Modifier.fillMaxWidth())
   Button(enabled = !busy && query.trim().length >= 4, onClick = {
    busy = true
    results = emptyList()
    status = "Поиск в OpenStreetMap…"
    scope.launch {
     try {
      val found = withContext(Dispatchers.IO) {
       val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=ru&addressdetails=1&limit=8&q=" + URLEncoder.encode(query.trim(), "UTF-8")
       val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
       val request = Request.Builder().url(url).header("User-Agent", "ZhkhPlus/2.1 (https://github.com/AnzorioReg/zhkh-app-public)").header("Accept-Language", "ru").build()
       client.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "HTTP ${response.code}" }
        val array = JSONArray(response.body?.string() ?: "[]")
        (0 until array.length()).map { array.getJSONObject(it) }.distinctBy { it.optString("osm_type") + it.optString("osm_id") }
       }
      }
      results = found
      status = if(found.isEmpty()) "В этом источнике адрес не найден. Уточните город или введите адрес вручную. Это не означает, что адреса не существует." else "Выберите точный адрес: ${found.size} вариантов"
     } catch(e: Exception) { status = "Не удалось выполнить поиск: ${e.message}" }
     finally { busy = false }
    }
   }) { Text(if(busy) "Ищем…" else "Найти адрес") }
   Text(status)
   results.forEach { item ->
    val address = item.optJSONObject("address") ?: JSONObject()
    val city = address.optString("city").ifBlank { address.optString("town").ifBlank { address.optString("village").ifBlank { address.optString("state") } } }
    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
     val title = item.optString("display_name")
     details = "Город: $city\nРегион: ${address.optString("state")}\nИндекс: ${address.optString("postcode")}\nКоординаты: ${item.optString("lat")}, ${item.optString("lon")}\nИсточник: OpenStreetMap, ODbL\nОбновлено: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}"
     prefs.edit().putString("address", title).putString("house_details",details).putString("house_source_id", item.optString("osm_type") + ":" + item.optString("osm_id")).apply()
     onSelected(title)
     results = emptyList()
     status = "Дом выбран. Закрытые квитанции и долги этим поиском не получены."
    }) { Column { Text(city); Text(item.optString("display_name")) } }
   }
   if(details.isNotBlank()) Text(details)
   Text("OpenStreetMap — не официальный реестр. Возможны неполные или неточные сведения. Госуслуги и поставщики пока не подключены.", style = MaterialTheme.typography.bodySmall)
  }
 }
}
