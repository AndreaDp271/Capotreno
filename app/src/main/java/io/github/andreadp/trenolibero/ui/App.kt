package io.github.andreadp.trenolibero.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import io.github.andreadp.trenolibero.R
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/** Azioni di navigazione usate dalle schermate. */
class Nav(private val c: NavHostController) {
    fun back() { c.popBackStack() }

    fun train(number: String, originId: String?, dateMs: Long?) {
        val q = buildList {
            originId?.let { add("origin=${Uri.encode(it)}") }
            dateMs?.let { add("date=$it") }
        }.joinToString("&")
        c.navigate("train/${Uri.encode(number)}" + if (q.isEmpty()) "" else "?$q")
    }

    fun trainMap(number: String, originId: String, dateMs: Long) =
        c.navigate("trainmap/${Uri.encode(number)}/${Uri.encode(originId)}/$dateMs")

    fun station(id: String, name: String) =
        c.navigate("station/${Uri.encode(id)}?name=${Uri.encode(name)}")

    fun map(focusId: String) = c.navigate("map?focus=${Uri.encode(focusId)}")

    fun line(region: Int, ab: Int, ba: Int) = c.navigate("line/$region/$ab/$ba")

    fun ticket(id: String) = c.navigate("ticket/${Uri.encode(id)}")

    fun scan() = c.navigate("scan")

    fun journey() = c.navigate("journey")
}

/** Voce della barra: icona Material o disegno vettoriale dell'app. */
private data class Tab(val route: String, val label: String, val icon: ImageVector? = null, val drawable: Int = 0)

private val tabs = listOf(
    Tab("home", "Treni", drawable = R.drawable.ic_tab_train),
    Tab("stations", "Stazioni", drawable = R.drawable.ic_tab_station),
    Tab("map", "Mappa", Icons.Default.Place),
    Tab("tickets", "Biglietti", drawable = R.drawable.ic_tab_ticket),
    Tab("info", "Info", Icons.Default.Info),
)

/**
 * [pendingImport]: PDF ricevuto da un'altra app (condividi/apri con), da importare come biglietto.
 */
@Composable
fun App(pendingImport: Uri? = null, onImportHandled: () -> Unit = {}) {
    val controller = rememberNavController()
    val nav = remember(controller) { Nav(controller) }
    var importError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingImport) {
        val uri = pendingImport ?: return@LaunchedEffect
        importTicket(uri).onSuccess { nav.ticket(it.id) }.onFailure { importError = errorMessage(it) }
        onImportHandled()
    }
    importError?.let { msg ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Importazione non riuscita") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } },
        )
    }
    val entry by controller.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val onTab = tabs.any { it.route == route }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (onTab) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            controller.navigate(t.route) {
                                popUpTo(controller.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (t.icon != null) Icon(t.icon, null) else Icon(painterResource(t.drawable), null)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(controller, startDestination = "home", modifier = Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            composable("home") { TrainSearchScreen(nav) }
            composable("stations") { StationSearchScreen(nav) }
            composable("map") { StationsMapScreen(nav, null) }
            composable("info") { InfoScreen(nav) }
            composable("tickets") { TicketsScreen(nav) }
            composable("scan") { ScanScreen(nav) }
            composable("journey") { JourneyScreen(nav) }
            composable("ticket/{id}") { TicketScreen(nav, it.arguments!!.getString("id")!!) }
            composable("line/{region}/{ab}/{ba}") {
                val a = it.arguments!!
                LineScreen(nav, a.getString("region")!!.toInt(), a.getString("ab")!!.toInt(), a.getString("ba")!!.toInt())
            }
            composable(
                "map?focus={focus}",
                arguments = listOf(navArgument("focus") { type = NavType.StringType; nullable = true }),
            ) { StationsMapScreen(nav, it.arguments?.getString("focus")) }
            composable(
                "train/{num}?origin={origin}&date={date}",
                arguments = listOf(
                    navArgument("num") { type = NavType.StringType },
                    navArgument("origin") { type = NavType.StringType; nullable = true },
                    navArgument("date") { type = NavType.StringType; nullable = true },
                ),
            ) {
                val a = it.arguments!!
                TrainScreen(nav, a.getString("num")!!, a.getString("origin"), a.getString("date")?.toLongOrNull())
            }
            composable("trainmap/{num}/{origin}/{date}") {
                val a = it.arguments!!
                TrainMapScreen(nav, a.getString("num")!!, a.getString("origin")!!, a.getString("date")!!.toLong())
            }
            composable(
                "station/{id}?name={name}",
                arguments = listOf(navArgument("name") { type = NavType.StringType; defaultValue = "" }),
            ) {
                val a = it.arguments!!
                val id = a.getString("id")!!
                StationBoardScreen(nav, id, a.getString("name").orEmpty().ifEmpty { id })
            }
        }
    }
}
