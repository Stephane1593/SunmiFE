package com.example.sunmisfe.ui.theme

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.sunmisfe.data.TerminalConfig
import com.example.sunmisfe.network.OrderDto
import com.example.sunmisfe.network.PaymentGatewayClient
import com.example.sunmisfe.network.SfeApiClient
import com.example.sunmisfe.payment.PaymentEngine
import com.example.sunmisfe.payment.PaymentEvent
import com.example.sunmisfe.payment.PaymentExtra
import com.example.sunmisfe.payment.PaymentMethod
import com.example.sunmisfe.printing.ReceiptPrinter
import com.example.sunmisfe.util.OfflineQrDecoder
import com.example.sunmisfe.util.ScannedQrStore
import com.example.sunmisfe.util.rememberQrScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

// ─────────────────────────── Dynamic Theming ───────────────────────────
enum class ThemePalette(val displayName: String, val primary: Color, val dark: Color) {
    BLUE("Bleu (Défaut)", Color(0xFF1565C0), Color(0xFF0D47A1)),
    RED("Rouge", Color(0xFFC62828), Color(0xFF8E0000)),
    GREEN("Vert", Color(0xFF2E7D32), Color(0xFF005005)),
    DARK("Sombre", Color(0xFF212121), Color(0xFF000000)),
    ORANGE("Orange", Color(0xFFEF6C00), Color(0xFFE65100))
}

var activeTheme by mutableStateOf(ThemePalette.BLUE)

private val SuccessGreen = Color(0xFF2E7D32)
private val SuccessBg = Color(0xFFE8F5E9)
private val ErrorRed = Color(0xFFC62828)
private val ErrorBg = Color(0xFFFDECEA)
private val NeutralBg = Color(0xFFF5F6FA)
private val CardStroke = Color(0xFFE3E6ED)

private sealed interface Screen {
    data object Orders : Screen
    data object Settings : Screen
    data class Pay(val order: OrderDto) : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosApp() {
    val ctx = LocalContext.current
    var screen: Screen by remember { mutableStateOf(Screen.Orders) }
    var isOnline by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        TerminalConfig.init(ctx)
        activeTheme = ThemePalette.entries.find { it.name == TerminalConfig.appTheme } ?: ThemePalette.BLUE
    }

    Scaffold(
        containerColor = NeutralBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "iSFE  •  Terminal de paiement",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                        Spacer(Modifier.width(12.dp))
                        // 💡 Connection Status Dot
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isOnline) Color(0xFF00E676) else Color(0xFFFF1744))
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = activeTheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                navigationIcon = {
                    if (screen !is Screen.Orders) {
                        IconButton(onClick = { screen = Screen.Orders }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    }
                },
                actions = {
                    if (screen is Screen.Orders) {
                        IconButton(onClick = { screen = Screen.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Paramètres")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = screen) {
                Screen.Orders -> OrdersScreen(
                    onOrderClick = { screen = Screen.Pay(it) },
                    onStatusChange = { isOnline = it }
                )
                Screen.Settings -> SettingsScreen(onDone = { screen = Screen.Orders })
                is Screen.Pay -> PaymentScreen(order = s.order, onDone = { screen = Screen.Orders })
            }
        }
    }
}

// ═══════════════════════════ ORDERS ═══════════════════════════

@Composable
private fun OrdersScreen(onOrderClick: (OrderDto) -> Unit, onStatusChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var apiOrders by remember { mutableStateOf<List<OrderDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    val scan = rememberQrScanner { token ->
        if (token == null) return@rememberQrScanner
        val payload = OfflineQrDecoder.decode(token)
        if (payload == null) {
            toast(ctx, "QR invalide ou signature incorrecte")
        } else {
            ScannedQrStore.add(payload)
            toast(ctx, "Commande chargée : ${payload.orderId}")
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                apiOrders = SfeApiClient.api.getOrders()
                error = null
                onStatusChange(true)
            } catch (e: Exception) {
                error = e.message
                onStatusChange(false)
            }
            delay(3000)
        }
    }

    val orders = remember(apiOrders, ScannedQrStore.scannedOrders.toList()) {
        val scanned = ScannedQrStore.scannedOrders
        val ids = scanned.map { it.orderId }.toSet()
        scanned + apiOrders.filter { it.orderId !in ids }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        Button(
            onClick = scan,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = activeTheme.primary)
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Scanner un QR", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))

        error?.let {
            InfoBanner("Caisse injoignable : $it", isError = true)
            Spacer(Modifier.height(12.dp))
        }

        if (orders.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.ReceiptLong, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = Color.LightGray
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Aucune commande en attente", color = Color.Gray,
                        fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Scannez un QR ou attendez la caisse",
                        color = Color.LightGray, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(orders, key = { it.orderId }) { order ->
                    OrderCard(order) { onOrderClick(order) }
                }
            }
        }
    }
}

@Composable
private fun InfoBanner(text: String, isError: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isError) ErrorBg else SuccessBg),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isError) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (isError) ErrorRed else SuccessGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(text, color = if (isError) ErrorRed else SuccessGreen, fontSize = 14.sp)
        }
    }
}

@Composable
private fun OrderCard(order: OrderDto, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var printing by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        border = BorderStroke(1.dp, CardStroke)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(activeTheme.primary))
                Spacer(Modifier.width(8.dp))
                Text(order.label, fontSize = 15.sp, color = Color.DarkGray, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${formatMoney(order.amount)} ${order.currency}",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A)
            )
            Spacer(Modifier.height(4.dp))
            Text("#${order.orderId}", fontSize = 13.sp, color = Color.Gray)

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = activeTheme.primary),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !cancelling && !printing
            ) {
                Icon(Icons.Filled.Payments, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Encaisser la commande", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            cancelling = true
                            try {
                                val success = SfeApiClient.cancelOrderSafe(order.orderId)
                                if (success) {
                                    ScannedQrStore.remove(order.orderId)
                                    toast(ctx, "✓ Commande annulée")
                                } else {
                                    toast(ctx, "❌ Erreur : impossible d'annuler")
                                }
                            } finally {
                                cancelling = false
                            }
                        }
                    },
                    enabled = !cancelling && !printing,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(46.dp)
                ) {
                    Text(if (cancelling) "Annulation…" else "Annuler", fontSize = 14.sp)
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            printing = true
                            val result = ReceiptPrinter.printProforma(order.orderId)
                            toast(ctx, if (result.isSuccess) "Proforma imprimée" else "Échec : ${result.exceptionOrNull()?.message}")
                            printing = false
                        }
                    },
                    enabled = !printing && !cancelling,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(46.dp)
                ) {
                    Text(if (printing) "Impression…" else "Proforma", fontSize = 14.sp)
                }
            }
        }
    }
}

// ═══════════════════════════ PAYMENT ═══════════════════════════

private sealed interface PayStep {
    data object PickMethod : PayStep
    data class EnterPhone(val method: PaymentMethod) : PayStep
    data object EnterCash : PayStep
    data class Running(val method: PaymentMethod, val extra: PaymentExtra?) : PayStep
}

@Composable
private fun PaymentScreen(order: OrderDto, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var step: PayStep by remember { mutableStateOf(PayStep.PickMethod) }
    var event by remember { mutableStateOf<PaymentEvent?>(null) }
    var printingProforma by remember { mutableStateOf(false) }
    var fiscalError by remember { mutableStateOf<String?>(null) }

    val runningStep = step as? PayStep.Running

    LaunchedEffect(runningStep) {
        val r = runningStep ?: return@LaunchedEffect
        event = null
        PaymentEngine.pay(order, r.method, r.extra).collect { event = it }
    }

    LaunchedEffect(event) {
        if (event is PaymentEvent.Succeeded) {
            val payload = ScannedQrStore.get(order.orderId)
            val selectedMethod = (step as? PayStep.Running)?.method?.display

            val result = when {
                payload != null && payload.documents?.isNotEmpty() == true -> {
                    ReceiptPrinter.printFromQr(payload.documents)
                }
                else -> {
                    ReceiptPrinter.printFiscalWithRetry(order.orderId, paymentMethod = selectedMethod)
                }
            }

            fiscalError = if (result.isSuccess) null
            else result.exceptionOrNull()?.message
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = activeTheme.primary)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(activeTheme.primary, activeTheme.dark)))
                    .padding(22.dp)
            ) {
                Column {
                    Text(order.label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${formatMoney(order.amount)} ${order.currency}",
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("#${order.orderId}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        if (step is PayStep.PickMethod) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        printingProforma = true
                        val result = ReceiptPrinter.printProforma(order.orderId)
                        toast(ctx, if (result.isSuccess) "Proforma imprimée" else "Échec proforma : ${result.exceptionOrNull()?.message}")
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
            Spacer(Modifier.height(20.dp))
        }

        when (val s = step) {
            is PayStep.PickMethod -> MethodPicker { picked ->
                step = when (picked) {
                    PaymentMethod.MPesa, PaymentMethod.Airtel, PaymentMethod.Orange -> PayStep.EnterPhone(picked)
                    PaymentMethod.Cash -> PayStep.EnterCash
                    PaymentMethod.Card -> PayStep.Running(picked, null)
                }
            }

            is PayStep.EnterPhone -> PhoneEntryPanel(
                method = s.method,
                onBack = { step = PayStep.PickMethod },
                onConfirm = { phone -> step = PayStep.Running(s.method, PaymentExtra.MobileMoney(phone)) }
            )

            is PayStep.EnterCash -> CashEntryPanel(
                due = order.amount,
                currency = order.currency,
                onBack = { step = PayStep.PickMethod },
                onConfirm = { tendered -> step = PayStep.Running(PaymentMethod.Cash, PaymentExtra.Cash(tendered)) }
            )

            is PayStep.Running -> when (val e = event) {
                null, PaymentEvent.Initiating -> ProgressPanel("Initialisation…")
                PaymentEvent.AwaitingApproval -> ApprovalPanel(
                    onApprove = { PaymentEngine.approve() },
                    onDecline = { PaymentEngine.decline() }
                )
                PaymentEvent.AwaitingCustomerConfirmation -> WaitingCustomerPanel(method = s.method)
                is PaymentEvent.Processing -> ProgressPanel(e.message)
                is PaymentEvent.Succeeded -> ResultPanel(
                    success = true,
                    title = "Paiement réussi",
                    lines = listOfNotNull(
                        "Méthode : ${s.method.display}",
                        "Autorisation : ${e.authCode}",
                        "Référence : ${e.transactionRef}",
                        fiscalError?.let { "⚠ Reçu fiscal : $it" }
                    ),
                    onDone = onDone,
                    onRetry = null,
                    onReprint = {
                        scope.launch {
                            val selectedMethod = (step as? PayStep.Running)?.method?.display
                            val result = ReceiptPrinter.printFiscalWithRetry(order.orderId, paymentMethod = selectedMethod)
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
                    onRetry = { step = PayStep.PickMethod; event = null },
                    onReprint = null
                )
            }
        }
    }
}

// ── Method picker — selectable image tiles ──

private data class MethodTile(val method: PaymentMethod, val icon: ImageVector, val color: Color, val imageUrl: String? = null)

private val methodTiles = listOf(
    MethodTile(PaymentMethod.Card, Icons.Filled.CreditCard, Color(0xFF1565C0)),
    MethodTile(PaymentMethod.MPesa, Icons.Filled.PhoneAndroid, Color(0xFF2E7D32)),
    MethodTile(PaymentMethod.Airtel, Icons.Filled.PhoneAndroid, Color(0xFFE53935)),
    MethodTile(PaymentMethod.Orange, Icons.Filled.PhoneAndroid, Color(0xFFF57C00)),
    MethodTile(PaymentMethod.Cash, Icons.Filled.Payments, Color(0xFF455A64)),
)

@Composable
private fun MethodPicker(onPick: (PaymentMethod) -> Unit) {
    var selected by remember { mutableStateOf<PaymentMethod?>(null) }

    Column {
        Text("Choisir un mode de paiement", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Sélectionnez une méthode pour continuer", fontSize = 14.sp, color = Color.Gray)
        Spacer(Modifier.height(16.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(methodTiles) { tile ->
                MethodTileView(
                    tile = tile,
                    isSelected = selected == tile.method,
                    onClick = { selected = tile.method }
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = { selected?.let(onPick) },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = activeTheme.primary)
        ) {
            Text(
                selected?.let { "Continuer avec ${it.display}" } ?: "Sélectionnez une méthode",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun MethodTileView(tile: MethodTile, isSelected: Boolean, onClick: () -> Unit) {
    val borderColor = if (isSelected) tile.color else CardStroke
    val bgColor = if (isSelected) tile.color.copy(alpha = 0.08f) else Color.White

    Column(
        modifier = Modifier
            .width(110.dp)
            .height(115.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(width = if (isSelected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(16.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(tile.color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            if (tile.imageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(tile.imageUrl).crossfade(true).build(),
                    contentDescription = tile.method.display,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Icon(tile.icon, contentDescription = tile.method.display, tint = tile.color, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            tile.method.display,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) tile.color else Color(0xFF333333),
            maxLines = 1
        )
    }
}

@Composable
private fun PhoneEntryPanel(method: PaymentMethod, onBack: () -> Unit, onConfirm: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    val isValid = phone.trim().length >= 9

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Retour") }
            Spacer(Modifier.width(4.dp))
            Text("${method.display}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Text("Saisir le numéro de téléphone du client pour envoyer la demande de paiement", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(20.dp))

        Card(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CardStroke)) {
            Column(Modifier.padding(20.dp)) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' } },
                    label = { Text("Numéro de téléphone") },
                    placeholder = { Text("+243 8xx xxx xxx") },
                    leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("🔒 Ce numéro n'est utilisé que pour cette transaction et n'est pas conservé.", fontSize = 12.sp, color = Color.LightGray)
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onConfirm(phone.trim()) },
            enabled = isValid,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = activeTheme.primary)
        ) {
            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Envoyer la demande de paiement", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CashEntryPanel(due: Double, currency: String, onBack: () -> Unit, onConfirm: (Double) -> Unit) {
    var input by remember { mutableStateOf(if (due % 1.0 == 0.0) due.toLong().toString() else due.toString()) }
    val tendered = input.toDoubleOrNull() ?: 0.0
    val change = (tendered - due).coerceAtLeast(0.0)
    val isValid = tendered >= due

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Retour") }
            Spacer(Modifier.width(4.dp))
            Text("Paiement en espèces", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))

        Card(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CardStroke)) {
            Column(Modifier.padding(20.dp)) {
                Text("Montant dû", fontSize = 14.sp, color = Color.Gray)
                Text("${formatMoney(due)} $currency", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = input,
                    onValueChange = { v -> input = v.filter { it.isDigit() || it == '.' } },
                    label = { Text("Montant reçu") },
                    leadingIcon = { Icon(Icons.Filled.Payments, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(12.dp))
                // 💡 Quick amount chips including a distinct "Montant Exact" option
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { input = if (due % 1.0 == 0.0) due.toLong().toString() else due.toString() },
                        label = { Text("Montant exact") },
                        shape = RoundedCornerShape(8.dp),
                        colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = activeTheme.primary)
                    )
                    listOf(due.roundUpToNext(5.0), due.roundUpToNext(10.0), due.roundUpToNext(50.0))
                        .distinct()
                        .filter { it > due } // Only show rounding up if it's actually larger
                        .forEach { amt ->
                            AssistChip(
                                onClick = { input = if (amt % 1.0 == 0.0) amt.toLong().toString() else amt.toString() },
                                label = { Text(formatMoney(amt)) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                }

                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Monnaie à rendre", fontSize = 15.sp, color = Color.Gray)
                    Text(
                        "${formatMoney(change)} $currency",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isValid) SuccessGreen else Color.Gray
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onConfirm(tendered) },
            enabled = isValid,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Confirmer le paiement", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun Double.roundUpToNext(step: Double): Double =
    kotlin.math.ceil(this / step) * step

@Composable
private fun ProgressPanel(message: String) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(strokeWidth = 4.dp, modifier = Modifier.size(56.dp), color = activeTheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(message, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun WaitingCustomerPanel(method: PaymentMethod) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(Color(0xFFE3F2FD)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = activeTheme.primary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("En attente de confirmation", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Demandez au client de valider le paiement ${method.display} sur son téléphone", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(8.dp)), color = activeTheme.primary)
    }
}

@Composable
private fun ApprovalPanel(onApprove: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.CreditCard, contentDescription = null, modifier = Modifier.size(52.dp), tint = activeTheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Insérer / présenter la carte", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Simulation terminal — décision opérateur :", fontSize = 14.sp, color = Color.Gray)

        Spacer(Modifier.height(32.dp))
        Button(onClick = onApprove, modifier = Modifier.fillMaxWidth().height(72.dp), colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen), shape = RoundedCornerShape(14.dp)) {
            Text("✓  APPROUVER", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(14.dp)) {
            Text("✗  Refuser", fontSize = 17.sp, color = ErrorRed)
        }
    }
}

@Composable
private fun ResultPanel(success: Boolean, title: String, lines: List<String>, onDone: () -> Unit, onRetry: (() -> Unit)?, onReprint: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(88.dp).clip(CircleShape).background(if (success) SuccessBg else ErrorBg), contentAlignment = Alignment.Center) {
            Icon(if (success) Icons.Filled.CheckCircle else Icons.Filled.Cancel, contentDescription = null, tint = if (success) SuccessGreen else ErrorRed, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CardStroke)) {
            Column(Modifier.padding(16.dp)) {
                lines.forEach {
                    Text(it, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        onReprint?.let {
            OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
                Text("🖨  Réimprimer le reçu", fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
        }

        onRetry?.let {
            OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp)) {
                Text("Réessayer", fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
        }

        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = activeTheme.primary)) {
            Text("Terminer", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ═══════════════════════════ SETTINGS ═══════════════════════════

@Composable
private fun SettingsScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tillUrl by remember { mutableStateOf(TerminalConfig.tillUrl) }
    var terminalId by remember { mutableStateOf(TerminalConfig.terminalId) }
    var tillSaved by remember { mutableStateOf(false) }

    var gwUrl by remember { mutableStateOf(TerminalConfig.gatewayUrl) }
    var gwLogin by remember { mutableStateOf(TerminalConfig.gatewayLogin) }
    var gwPassword by remember { mutableStateOf(TerminalConfig.gatewayPassword) }
    var gwPasswordVisible by remember { mutableStateOf(false) }
    var gwSaved by remember { mutableStateOf(false) }

    var testPrinting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Paramètres", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        // ── Section: Theming ──
        SettingsSection(
            icon = Icons.Filled.Palette,
            title = "Thème de l'application",
            subtitle = "Sélectionner la couleur principale de l'interface."
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ThemePalette.entries) { themeOption ->
                    FilterChip(
                        selected = activeTheme == themeOption,
                        onClick = {
                            activeTheme = themeOption
                            TerminalConfig.updateTheme(ctx, themeOption.name)
                        },
                        label = { Text(themeOption.displayName) },
                        leadingIcon = {
                            Box(Modifier.size(16.dp).clip(CircleShape).background(themeOption.primary))
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // ── Section: Till pairing ──
        SettingsSection(
            icon = Icons.Filled.Storefront,
            title = "Appairage de la caisse",
            subtitle = "URL de la caisse SFE et identifiant de ce terminal."
        ) {
            OutlinedTextField(
                value = tillUrl, onValueChange = { tillUrl = it; tillSaved = false },
                label = { Text("URL de l'API caisse (http://ip:port)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = terminalId, onValueChange = { terminalId = it; tillSaved = false },
                label = { Text("Identifiant du terminal") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    TerminalConfig.updateTill(ctx, tillUrl, terminalId)
                    SfeApiClient.updateConfig(ctx, tillUrl, terminalId)
                    tillSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = activeTheme.primary)
            ) {
                Text("Enregistrer la caisse", fontSize = 15.sp)
            }
            if (tillSaved) {
                Spacer(Modifier.height(10.dp))
                InfoBanner("✓ Paramètres caisse enregistrés", isError = false)
            }
        }
        Spacer(Modifier.height(20.dp))

        // ── Section: Payment gateway credentials ──
        SettingsSection(
            icon = Icons.Filled.Lock,
            title = "Passerelle de paiement mobile",
            subtitle = "Identifiants du compte API pour M-Pesa / Airtel / Orange."
        ) {
            OutlinedTextField(
                value = gwUrl, onValueChange = { gwUrl = it; gwSaved = false },
                label = { Text("URL de la passerelle") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = gwLogin, onValueChange = { gwLogin = it; gwSaved = false },
                label = { Text("Identifiant (login)") },
                singleLine = true, leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = gwPassword, onValueChange = { gwPassword = it; gwSaved = false },
                label = { Text("Mot de passe") },
                singleLine = true, leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { gwPasswordVisible = !gwPasswordVisible }) {
                        Icon(if (gwPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null)
                    }
                },
                visualTransformation = if (gwPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    TerminalConfig.updateGateway(ctx, gwUrl, gwLogin, gwPassword)
                    PaymentGatewayClient.invalidate()
                    gwSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = activeTheme.dark)
            ) {
                Text("Enregistrer la passerelle", fontSize = 15.sp)
            }
            if (gwSaved) {
                Spacer(Modifier.height(10.dp))
                InfoBanner("✓ Identifiants de la passerelle enregistrés", isError = false)
            }
        }
        Spacer(Modifier.height(20.dp))

        // ── Section: Printer test ──
        SettingsSection(
            icon = Icons.Filled.Print,
            title = "Test d'impression",
            subtitle = "Vérifier que l'imprimante Sunmi répond correctement."
        ) {
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
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)
            ) {
                if (testPrinting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Test en cours…")
                } else {
                    Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Tester l'imprimante", fontSize = 15.sp)
                }
            }

            testResult?.let {
                Spacer(Modifier.height(12.dp))
                InfoBanner(it, isError = !it.startsWith("✓"))
            }
        }
        Spacer(Modifier.height(28.dp))

        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
            Text("Retour", fontSize = 16.sp)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsSection(icon: ImageVector, title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, CardStroke), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(activeTheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = activeTheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(subtitle, fontSize = 13.sp, color = Color.Gray)
            Spacer(Modifier.height(16.dp))
            content()
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