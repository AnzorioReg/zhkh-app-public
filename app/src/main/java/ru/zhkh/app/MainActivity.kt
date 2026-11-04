package ru.zhkh.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val prefs = getSharedPreferences("zhkh", MODE_PRIVATE)
  setContent {
   var dark by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
   var address by remember { mutableStateOf(prefs.getString("address", "") ?: "") }
   var raw by remember { mutableStateOf(prefs.getString("ledger_v1", "[]") ?: "[]") }
   var tab by remember { mutableStateOf(0) }
   var category by remember { mutableStateOf("Электричество") }
   var amount by remember { mutableStateOf("") }
   var date by remember { mutableStateOf(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())) }
   var year by remember { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR).toString()) }
   var error by remember { mutableStateOf("") }
   val arr = remember(raw) { try { JSONArray(raw) } catch(e: Exception) { JSONArray() } }
   val rows = (0 until arr.length()).map { arr.getJSONObject(it) }
   val annual = rows.filter { it.optString("date").startsWith(year + "-") }
   val totals = annual.groupBy { it.optString("category") }.mapValues { entry -> entry.value.sumOf { it.optDouble("amount",0.0) } }
   fun store(next: JSONArray) { raw = next.toString(); prefs.edit().putString("ledger_v1",raw).apply() }
   fun report() = "ЖКХ — $year\n$address\n" + totals.entries.joinToString("\n") { "${it.key}: ${String.format(java.util.Locale.getDefault(), "%.2f", it.value)} ₽" }
   MaterialTheme(colorScheme = if(dark) darkColorScheme() else lightColorScheme()) {
    Surface(Modifier.fillMaxSize()) {
     Column(Modifier.fillMaxSize().systemBarsPadding()) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
       Text("ЖКХ Плюс", fontSize = 30.sp)
       Text(listOf("Моя квартира","Платежи","Годовая сводка","Настройки")[tab], fontSize = 22.sp)
       when(tab) {
        0 -> {
         OutlinedTextField(address, { address = it }, label = { Text("Город, улица, дом, квартира") }, modifier = Modifier.fillMaxWidth())
         Button(onClick = { prefs.edit().putString("address",address).apply(); error = "Адрес сохранён" }) { Text("Сохранить квартиру") }
         Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
          Text("Оплачено за $year")
          Text("%.2f ₽".format(totals.values.sum()), fontSize = 32.sp)
          Text("Платежей: ${annual.size}")
         } }
         Button(onClick = { tab = 1 }) { Text("Добавить платёж") }
         Text("Автоматическое получение данных Госуслуг не подключено. Здесь отображаются ваши сохранённые платежи.")
        }
        1 -> {
         OutlinedTextField(category, { category = it }, label = { Text("Услуга, например газ или интернет") }, modifier = Modifier.fillMaxWidth())
         OutlinedTextField(amount, { amount = it }, label = { Text("Сумма в рублях") }, modifier = Modifier.fillMaxWidth())
         OutlinedTextField(date, { date = it }, label = { Text("Дата: ГГГГ-ММ-ДД") }, modifier = Modifier.fillMaxWidth())
         Button(onClick = {
          val value = amount.replace(',', '.').toDoubleOrNull()
          val validDate = try { val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US); f.isLenient = false; date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && f.parse(date) != null } catch(e: Exception) { false }
          if(value == null || !value.isFinite() || value <= 0 || category.isBlank() || !validDate) error = "Проверьте услугу, сумму и дату"
          else { val next = JSONArray(raw); next.put(JSONObject().put("category",category.trim()).put("amount",value).put("date",date)); store(next); amount = ""; error = "Платёж сохранён" }
         }) { Text("Сохранить платёж") }
         rows.indices.reversed().forEach { index ->
          val row = rows[index]
          Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
           Text(row.optString("category"), fontSize = 18.sp)
           Text("${row.optString("date")} · ${row.optDouble("amount")} ₽")
           TextButton(onClick = { val next = JSONArray(); rows.forEachIndexed { i,r -> if(i != index) next.put(r) }; store(next) }) { Text("Удалить") }
          } }
         }
        }
        2 -> {
         OutlinedTextField(year, { year = it }, label = { Text("Год") })
         Text("Всего: %.2f ₽".format(totals.values.sum()), fontSize = 26.sp)
         totals.entries.sortedByDescending { it.value }.forEach { entry ->
          Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(entry.key); Text("%.2f ₽".format(entry.value)); Text("%.1f%% расходов".format(100 * entry.value / totals.values.sum())) } }
         }
         if(annual.isEmpty()) Text("За этот год пока нет сохранённых платежей")
         Button(onClick = { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report()) }, "Отправить отчёт")) }) { Text("Поделиться сводкой") }
        }
        3 -> {
         Text("Тёмная тема")
         Switch(checked = dark, onCheckedChange = { dark = it; prefs.edit().putBoolean("dark",it).apply() })
         Text("Платежи и адрес сохраняются на устройстве. Демо-данных нет. Вход и синхронизация Госуслуг пока не реализованы.")
        }
       }
       if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.primary)
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
       listOf("Главная","Платежи","Сводка","Ещё").forEachIndexed { index, label -> TextButton(onClick = { tab = index; error = "" }) { Text(label) } }
      }
     }
    }
   }
  }
 }
}
