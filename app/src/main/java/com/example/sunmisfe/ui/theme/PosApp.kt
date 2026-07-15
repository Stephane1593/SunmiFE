package com.example.sunmisfe.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sunmisfe.network.OrderDto
import com.example.sunmisfe.network.SfeApiClient
import com.example.sunmisfe.payment.PaymentEngine
import com.example.sunmisfe.payment.PaymentEvent
import com.example.sunmisfe.payment.PaymentMethod
import com.example.sunmisfe.printing.ReceiptPrinter
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private sealed interface Screen {
    data object Orders : Screen
    data object Settings : Screen
    data class Pay(val order: OrderDto) : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosApp() {
    var screen: Screen by remember { mutableStateOf(Screen.Orders) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SFE — Terminal de paiement") },
                navigationIcon = {
                    if (screen !is Screen.Orders) {
                        TextButton(onClick = { screen = Screen.Orders }) {
                            Text("← Retour")
                        }
                    }
                },
                actions = {
                    if (screen is Screen.Orders) {
                        TextButton(onClick = { screen = Screen.Settings }) {
                            Text("⚙  Paramètres")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = screen) {
                Screen.Orders   -> OrdersScreen(onOrderClick = { screen = Screen.Pay(it) })
                Screen.Settings -> SettingsScreen(onDone = { screen = Screen.Orders })
                is Screen.Pay   -> PaymentScreen(order = s.order, onDone = { screen = Screen.Orders })
            }
        }
    }
}

@Composable
private fun OrdersScreen(onOrderClick: (OrderDto) -> Unit) {
    val scope = rememberCoroutineScope()
    var orders by remember { mutableStateOf<List<OrderDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        while (true) {
            try {
                orders = SfeApiClient.api.getOrders()
                error = null
            } catch (e: Exception) {
                error = e.message
            }
            delay(3000)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "En attente",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                scope.launch {
                    loading = true
                    try {
                        orders = SfeApiClient.api.getOrders()
                        error = null
                    } catch (e: Exception) {
                        error = e.message
                    }
                    loading = false
                }
            }) {
                if (loading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                        color = Color.White
                    )
                } else {
                    Text("Actualiser")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFDECEA))) {
                Text("Erreur : $it", Modifier.padding(12.dp), color = Color(0xFF8B0000))
            }
            Spacer(Modifier.height(12.dp))
        }

        if (orders.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Aucune commande en attente.\nLa caisse doit valider une vente.",
                    color = Color.Gray,
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(orders, key = { it.orderId }) { order ->
                    OrderCard(order) { onOrderClick(order) }
                }
            }
        }
    }
}

@Composable
private fun OrderCard(order: OrderDto, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var printing by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(order.label, fontSize = 14.sp, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatMoney(order.amount)} ${order.currency}",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text("#${order.orderId}", fontSize = 12.sp, color = Color.Gray)

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            printing = true
                            val result = ReceiptPrinter.printProforma(order.orderId)
                            if (result.isSuccess) {
                                toast(ctx, "Proforma imprimée")
                            } else {
                                toast(ctx, "Échec : ${result.exceptionOrNull()?.message}")
                            }
                            printing = false
                        }
                    },
                    enabled = !printing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (printing) "Impression…" else "🧾 Proforma")
                }
                Button(
                    onClick = onClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Encaisser")
                }
            }
        }
    }
}

@Composable
private fun PaymentScreen(order: OrderDto, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var chosen by remember { mutableStateOf<PaymentMethod?>(null) }
    var event by remember { mutableStateOf<PaymentEvent?>(null) }
    var printingProforma by remember { mutableStateOf(false) }
    var fiscalError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(chosen) {
        val method = chosen ?: return@LaunchedEffect
        PaymentEngine.pay(order, method).collect { event = it }
    }

    // Auto-print fiscal receipt as soon as payment succeeds
    LaunchedEffect(event) {
        if (event is PaymentEvent.Succeeded) {
            val result = ReceiptPrinter.printFiscalWithRetry(order.orderId)
            if (result.isSuccess) {
                fiscalError = null
            } else {
                fiscalError = result.exceptionOrNull()?.message
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E88E5))
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(order.label, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatMoney(order.amount)} ${order.currency}",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── PROFORMA button — visible only before a method is chosen ──
        if (event == null) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        printingProforma = true
                        val result = ReceiptPrinter.printProforma(order.orderId)
                        if (result.isSuccess) {
                            toast(ctx, "Proforma imprimée")
                        } else {
                            toast(ctx, "Échec proforma : ${result.exceptionOrNull()?.message}")
                        }
                        printingProforma = false
                    }
                },
                enabled = !printingProforma,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (printingProforma) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Impression…")
                } else {
                    Text("🧾  Imprimer proforma", fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        when (val e = event) {
            null -> MethodPicker { chosen = it }
            PaymentEvent.Initiating -> ProgressPanel("Initialisation…")
            PaymentEvent.AwaitingApproval -> ApprovalPanel(
                onApprove = { PaymentEngine.approve() },
                onDecline = { PaymentEngine.decline() }
            )
            is PaymentEvent.Processing -> ProgressPanel(e.message)
            is PaymentEvent.Succeeded -> ResultPanel(
                success = true,
                title = "Paiement réussi",
                lines = listOfNotNull(
                    "Méthode : ${chosen?.display ?: "—"}",
                    "Autorisation : ${e.authCode}",
                    "Référence : ${e.transactionRef}",
                    fiscalError?.let { "⚠ Reçu fiscal : $it" }
                ),
                onDone = onDone,
                onRetry = null,
                // extra action: reprint fiscal
                onReprint = {
                    scope.launch {
                        val result = ReceiptPrinter.printFiscalWithRetry(order.orderId)
                        if (result.isSuccess) {
                            fiscalError = null
                            toast(ctx, "Reçu fiscal réimprimé")
                        } else {
                            fiscalError = result.exceptionOrNull()?.message
                            toast(ctx, "Échec : ${result.exceptionOrNull()?.message}")
                        }
                    }
                }
            )
            is PaymentEvent.Failed -> ResultPanel(
                success = false,
                title = "Paiement échoué",
                lines = listOf("Motif : ${e.reason}"),
                onDone = onDone,
                onRetry = { event = null; chosen = null },
                onReprint = null
            )
        }
    }
}

@Composable
private fun MethodPicker(onPick: (PaymentMethod) -> Unit) {
    Column {
        Text("Choisir un mode de paiement", fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PaymentMethod.entries.forEach { m ->
                Button(
                    onClick = { onPick(m) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(m.display, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun ProgressPanel(message: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(strokeWidth = 4.dp, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text(message, fontSize = 16.sp)
    }
}

@Composable
private fun ApprovalPanel(onApprove: () -> Unit, onDecline: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Insérer / présenter la carte",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Simulation terminal — décision opérateur :",
            fontSize = 13.sp,
            color = Color.Gray
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onApprove,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("✓  APPROUVER", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onDecline,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("✗  Refuser", fontSize = 18.sp, color = Color(0xFFC62828))
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ResultPanel(
    success: Boolean,
    title: String,
    lines: List<String>,
    onDone: () -> Unit,
    onRetry: (() -> Unit)?,
    onReprint: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Text(
            if (success) "✓" else "✗",
            fontSize = 72.sp,
            color = if (success) Color(0xFF2E7D32) else Color(0xFFC62828),
            fontWeight = FontWeight.Bold
        )
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                lines.forEach {
                    Text(it, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        Spacer(Modifier.weight(1f))

        onReprint?.let {
            OutlinedButton(
                onClick = it,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("🖨  Réimprimer le reçu", fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
        }

        onRetry?.let {
            OutlinedButton(
                onClick = it,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Réessayer", fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Terminer", fontSize = 18.sp)
        }
    }
}

@Composable
private fun SettingsScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(SfeApiClient.currentUrl()) }
    var id by remember { mutableStateOf(SfeApiClient.terminalId) }
    var saved by remember { mutableStateOf(false) }
    var testPrinting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Appairage du terminal", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Renseignez l'URL de la caisse SFE et l'identifiant de ce terminal. "
                    + "L'identifiant doit être identique à celui saisi côté caisse.",
            fontSize = 13.sp,
            color = Color.Gray
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it; saved = false },
            label = { Text("URL de l'API (http://ip:port)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = id,
            onValueChange = { id = it; saved = false },
            label = { Text("Identifiant du terminal") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                SfeApiClient.updateConfig(ctx, url, id)
                saved = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Enregistrer", fontSize = 16.sp)
        }

        if (saved) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                Text(
                    "✓ Paramètres enregistrés. Les prochaines requêtes utiliseront ce terminal.",
                    Modifier.padding(12.dp),
                    color = Color(0xFF1B5E20)
                )
            }
        }

        // ── TEST PRINT BUTTON ──
        Spacer(Modifier.height(24.dp))
        Text("Test d'impression", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                scope.launch {
                    testPrinting = true
                    testResult = null
                    try {
                        ReceiptPrinter.testPrint()
                        testResult = "✓ Test d'impression réussi!"
                    } catch (e: Exception) {
                        testResult = "✗ Erreur : ${e.message}"
                    }
                    testPrinting = false
                }
            },
            enabled = !testPrinting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (testPrinting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = Color.White
                )
                Spacer(Modifier.width(8.dp))
                Text("Test en cours…")
            } else {
                Text("🖨  Tester l'imprimante", fontSize = 16.sp)
            }
        }

        testResult?.let {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (it.startsWith("✓")) Color(0xFFE8F5E9) else Color(0xFFFDECEA)
                )
            ) {
                Text(
                    it,
                    Modifier.padding(12.dp),
                    color = if (it.startsWith("✓")) Color(0xFF1B5E20) else Color(0xFF8B0000)
                )
            }
        }

        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("Retour", fontSize = 16.sp)
        }
    }
}

private fun formatMoney(v: Double): String =
    NumberFormat.getNumberInstance(Locale.FRANCE).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(v)

private fun toast(ctx: android.content.Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}