package ru.zhkh.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val addressHttp = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
private suspend fun suggestAddresses(token: String, query: String): String = suspendCancellableCoroutine { cont ->
    val body = JSONObject().put("query", query).put("count", 8).toString()
    val request = Request.Builder().url("https://suggestions.dadata.ru/suggestions/api/4_1/rs/suggest/address")
        .header("Authorization", "Token $token").header("Accept", "application/json")
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    val call = addressHttp.newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWith(Result.failure(IOException("Не удалось подключиться к сервису адресов")))
        }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val text = it.body?.string().orEmpty()
                val error = when (it.code) {
                    401, 403 -> "Проверьте API-ключ, подтверждение почты и доступ к подсказкам"
                    429 -> "Лимит запросов. Подождите перед следующей попыткой"
                    else -> "Сервис адресов: HTTP ${it.code}"
                }
                if (cont.isActive) cont.resumeWith(if (it.isSuccessful) Result.success(text) else Result.failure(IOException(error)))
            }
        }
    })
}

@Composable
fun AddressSearch(context: Context, onSelected: (String) -> Unit) {
    val prefs = remember { context.getSharedPreferences("zhkh", Context.MODE_PRIVATE) }
    var tokenInput by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<JSONObject?>(null) }
    var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var status by remember { mutableStateOf("Подключите DaData своим API-ключом") }
    var busy by remember { mutableStateOf(false) }
    val cache = remember(token) { LinkedHashMap<String, String>() }
    val base = chosen?.optString("value").orEmpty()
    val suffix = if (base.isNotBlank() && query.startsWith(base)) query.removePrefix(base).trim().trimStart(',').trim() else ""
    val localFlat = if (suffix.matches(Regex("[0-9]+[А-Яа-яA-Za-z]?(?:[-/][0-9]+)?"))) suffix else extractFlatNumber(suffix)
    val chosenHasHouse = chosen?.optJSONObject("data")?.optString("house").orEmpty().let { it.isNotBlank() && it != "null" }
    val canSave = chosenHasHouse && (query == base || (query.startsWith(base) && localFlat.isNotBlank()))
    LaunchedEffect(query, token) {
        results = emptyList()
        busy = false
        if (token.isBlank() || query.trim().length < 3) return@LaunchedEffect
        if (canSave) { status = "Адрес выбран. Можно дописать квартиру в этой же строке."; return@LaunchedEffect }
        delay(400)
        busy = true
        try {
            val text = cache[query] ?: suggestAddresses(token, query).also {
                if (cache.size >= 100) cache.remove(cache.keys.first())
                cache[query] = it
            }
            val array = JSONObject(text).optJSONArray("suggestions")
            results = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            status = if (results.isEmpty()) "Нет вариантов. Уточните город и адрес или сохраните вручную." else "Выберите адрес или продолжайте ввод"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { status = e.message ?: "Ошибка поиска" }
        finally { busy = false }
    }
    fun saveAddress(manual: Boolean) {
        val data = if (manual) JSONObject() else JSONObject(chosen?.optJSONObject("data")?.toString() ?: "{}")
        val flat = if (manual) extractFlatNumber(query) else localFlat.ifBlank { data.optString("flat").takeUnless { it == "null" }.orEmpty() }
        val full = if (!manual && localFlat.isNotBlank()) "$base, кв. $localFlat" else query.trim()
        if (localFlat.isNotBlank()) { data.put("flat", localFlat); data.remove("flat_fias_id"); data.remove("flat_cadnum") }
        val saved = prefs.getString("apartments_list", "").orEmpty().lines().filter { it.isNotBlank() }.toMutableSet()
        saved.add(full)
        prefs.edit().putString("address", full).putString("apartment_number", flat)
            .putString("apartments_list", saved.joinToString("\n"))
            .putString("address_data:$full", data.toString())
            .putString("address_source:$full", if (manual) "manual" else "dadata")
            .putLong("address_saved_at:$full", System.currentTimeMillis()).apply()
        onSelected(full)
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Моя квартира", style = MaterialTheme.typography.headlineSmall)
        if (token.isBlank()) {
            OutlinedTextField(tokenInput, { tokenInput = it }, label = { Text("API-ключ DaData, не Secret") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Ключ используется только для запросов к DaData и хранится в памяти до закрытия экрана. Адрес ввода передаётся DaData.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = tokenInput.trim().matches(Regex("[a-zA-Z0-9]+")), onClick = { token = tokenInput.trim(); tokenInput = ""; status = "Введите адрес" }) { Text("Подключить подсказки") }
        } else TextButton(onClick = { token = ""; cache.clear(); results = emptyList(); status = "Подсказки отключены" }) { Text("Отключить DaData") }
        OutlinedTextField(query, { query = it }, label = { Text("Город, улица, дом, корпус, литера, квартира") }, supportingText = { Text("Например: ул Павлова д 10 к 2 кв 45") }, modifier = Modifier.fillMaxWidth())
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status)
        if (canSave) {
            Text(if (localFlat.isNotBlank()) "Квартира $localFlat введена вами, реестром не подтверждена" else "Выбранный адрес: $base")
            Button(onClick = { saveAddress(false) }) { Text("Сохранить и открыть") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results) { item ->
                OutlinedButton(onClick = { chosen = item; query = item.optString("value"); results = emptyList() }, modifier = Modifier.fillMaxWidth()) { Text(item.optString("value")) }
            }
        }
        if (query.trim().length >= 5) TextButton(onClick = { saveAddress(true) }) { Text("Сохранить введённый адрес без проверки") }
        Text("Подсказки: DaData. Ручной ввод не подтверждает наличие квартиры в реестре.", style = MaterialTheme.typography.bodySmall)
    }
}

fun normalizeQuery(input: String): String = input.trim().replace(Regex("\\s+"), " ")
fun extractFlatNumber(input: String): String = Regex("(?i)(?:^|[\\s,])(?:квартира|кв\\.?)\\s*([0-9]+[А-Яа-яA-Za-z]?(?:[-/][0-9]+)?)").find(input)?.groupValues?.get(1).orEmpty()
fun removeFlatFromQuery(input: String): String = input.replace(Regex("(?i)[,\\s]+(?:квартира|кв\\.?)\\s*[0-9].*$"), "").trim()
