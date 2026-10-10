package io.github.andreadp.trenolibero.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Treni", Icons.Default.Home),
    Tab("stations", "Stazioni", Icons.Default.Search),
    Tab("map", "Mappa", Icons.Default.Place),
    Tab("info", "Info", Icons.Default.Info),
)

@Composable
fun App() {
    val controller = rememberNavController()
    val nav = remember(controller) { Nav(controller) }
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
                        icon = { Icon(t.icon, null) },
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
