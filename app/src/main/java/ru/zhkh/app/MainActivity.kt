package ru.zhkh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    ApartmentApp()
                }
            }
        }
    }
}

@Composable
fun ApartmentApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("zhkh", android.content.Context.MODE_PRIVATE) }
    var screen by remember { mutableStateOf("search") }
    var address by remember { mutableStateOf(prefs.getString("address", "") ?: "") }
    var raw by remember { mutableStateOf(prefs.getString("ledger_v1", "[]") ?: "[]") }
    var category by remember { mutableStateOf("Электричество") }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("2026-11-05") }
    var message by remember { mutableStateOf("") }

    val payments = remember(raw) {
        try {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).map { array.getJSONObject(it) }
        } catch (e: Exception) {
            emptyList<org.json.JSONObject>()
        }
    }

    if (screen == "search") {
        AddressSearch(context) { selected ->
            address = selected
            screen = "apartment"
        }
        return
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B1220)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Моя квартира", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(address, color = Color(0xFF00D1B2))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF16233B))) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Данные квартиры", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Открытые геоданные: подключены", color = Color(0xFF9FB3C8))
                        Text("Госуслуги: требуется вход", color = Color(0xFF9FB3C8))
                        Text("ГИС ЖКХ: требуется доступ", color = Color(0xFF9FB3C8))
                        Button(onClick = { message = "Запрос доступа к Госуслугам требует регистрации приложения в ЕСИА." }, modifier = Modifier.fillMaxWidth()) {
                            Text("Запросить доступ к Госуслугам")
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF16233B))) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Квитанции и документы", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Загрузите PDF или фото квитанции. Документы сохраняются в приложении.", color = Color(0xFF9FB3C8))
                        Button(onClick = { message = "Импорт документов будет добавлен в следующей сборке." }, modifier = Modifier.fillMaxWidth()) {
                            Text("Загрузить документ")
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF16233B))) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Платежи", color = Color.White, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(category, { category = it }, label = { Text("Услуга") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(amount, { amount = it }, label = { Text("Сумма, ₽") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(date, { date = it }, label = { Text("Дата ГГГГ-ММ-ДД") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            val value = amount.replace(',', '.').toDoubleOrNull()
                            if (value == null || value <= 0) {
                                message = "Введите сумму"
                            } else {
                                val array = org.json.JSONArray(raw)
                                array.put(org.json.JSONObject()
                                    .put("category", category)
                                    .put("amount", value)
                                    .put("date", date)
                                    .put("address", address))
                                raw = array.toString()
                                prefs.edit().putString("ledger_v1", raw).apply()
                                amount = ""
                                message = "Платёж сохранён"
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить платёж") }
                    }
                }
            }

            items(payments.reversed()) { payment ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF16233B))) {
                    Column(Modifier.padding(16.dp)) {
                        Text(payment.optString("category"), color = Color.White)
                        Text("${payment.optString("date")} · ${payment.optDouble("amount", 0.0)} ₽", color = Color(0xFF9FB3C8))
                    }
                }
            }
        }

        if (message.isNotBlank()) Text(message, color = Color(0xFF00D1B2))

        Button(onClick = { screen = "search" }, modifier = Modifier.fillMaxWidth()) {
            Text("Найти другую квартиру")
        }
    }
}
