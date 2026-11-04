package ru.zhkh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    var tab by remember { mutableStateOf(0) }
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

    val tabs = listOf("Квартира", "Данные", "Платежи", "Документы")

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        label = { Text(label) },
                        icon = { Text(listOf("1", "2", "3", "4")[index]) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (tab) {
                0 -> AddressSearch(context) { selected ->
                    address = selected
                    tab = 1
                    message = "Адрес сохранён. Открыта вкладка «Данные»."
                }

                1 -> {
                    Text("Данные по адресу", style = MaterialTheme.typography.headlineSmall)
                    if (address.isBlank()) {
                        Text("Сначала выберите адрес на вкладке «Квартира».")
                    } else {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Адрес", style = MaterialTheme.typography.titleMedium)
                                Text(address)
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Источники", style = MaterialTheme.typography.titleMedium)
                                Text("Геоданные: подключены")
                                Text("Госуслуги: нужен вход и регистрация приложения")
                                Text("ГИС ЖКХ: нужен доступ оператора")
                                Text("Квитанции: загрузка документов будет следующей")
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Платежи", style = MaterialTheme.typography.titleMedium)
                                Text("Сохранено записей: ${payments.size}")
                            }
                        }
                    }
                }

                2 -> {
                    Text("Платежи", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(category, { category = it }, label = { Text("Услуга") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(amount, { amount = it }, label = { Text("Сумма, ₽") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(date, { date = it }, label = { Text("Дата ГГГГ-ММ-ДД") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        val value = amount.replace(',', '.').toDoubleOrNull()
                        if (value == null || value <= 0) {
                            message = "Введите сумму"
                        } else {
                            val array = org.json.JSONArray(raw)
                            array.put(
                                org.json.JSONObject()
                                    .put("category", category)
                                    .put("amount", value)
                                    .put("date", date)
                                    .put("address", address)
                            )
                            raw = array.toString()
                            prefs.edit().putString("ledger_v1", raw).apply()
                            amount = ""
                            message = "Платёж сохранён"
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить платёж") }

                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(payments.reversed()) { payment ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(payment.optString("category"))
                                    Text("${payment.optString("date")} · ${payment.optDouble("amount", 0.0)} ₽")
                                }
                            }
                        }
                    }
                }

                else -> {
                    Text("Документы", style = MaterialTheme.typography.headlineSmall)
                    Text("Здесь будут квитанции, выписки и документы по адресу.")
                    Text("Автоматическая загрузка из Госуслуг и ГИС ЖКХ требует отдельной регистрации интеграции.")
                }
            }

            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        }
    }
}
