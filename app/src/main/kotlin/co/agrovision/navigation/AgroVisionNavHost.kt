package co.agrovision.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import co.agrovision.R
import co.agrovision.feature.catalog.CatalogScreen
import co.agrovision.feature.catalog.CatalogViewModel
import co.agrovision.feature.catalog.PestDetailScreen
import co.agrovision.feature.catalog.PestDetailViewModel
import co.agrovision.home.HomeScreen
import co.agrovision.home.HomeViewModel

/**
 * Grafo de navegación.
 *
 * ## Qué se conserva de §6.4 y qué cambia
 *
 * **No hay inicio de sesión en ninguna parte del arranque.** Esa regla sigue
 * intacta: todo lo alcanzable desde aquí funciona sin cuenta y sin señal. La
 * cuenta se pide solo al intentar enviar una Solicitud de Revisión.
 *
 * Lo que cambia respecto al diseño original es que el destino inicial es Inicio y
 * no la cámara directamente. La razón la dio la primera prueba en dispositivo: si
 * el escaneo no puede responder —sin modelo, sin luz, o porque el productor solo
 * quiere consultar una ficha— abrir en la cámara deja la app sin salida.
 *
 * «Analizar» sigue siendo la acción dominante de la primera pantalla, a un toque.
 */
object AgroVisionDestinations {
    const val HOME = "inicio"
    const val SCAN = "escaneo"
    const val CATALOG = "catalogo"
    const val PEST_DETAIL = "catalogo/{pestId}"
    const val HISTORY = "historial"
    const val REVIEW_LIST = "solicitudes"
    const val REVIEW_CREATE = "solicitudes/nueva/{captureId}"
    const val SETTINGS = "ajustes"

    fun pestDetail(pestId: String) = "catalogo/$pestId"
    fun createReview(captureId: String) = "solicitudes/nueva/$captureId"
}

private data class BottomDestination(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
)

private val BOTTOM_DESTINATIONS = listOf(
    BottomDestination(AgroVisionDestinations.HOME, Icons.Filled.Home, R.string.nav_inicio),
    BottomDestination(
        AgroVisionDestinations.CATALOG,
        Icons.AutoMirrored.Filled.MenuBook,
        R.string.nav_plagas,
    ),
    BottomDestination(AgroVisionDestinations.HISTORY, Icons.Filled.History, R.string.nav_historial),
)

@Composable
fun AgroVisionNavHost(
    navController: NavHostController = rememberNavController(),
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination

    // La barra inferior desaparece en la cámara y en el detalle: son pantallas
    // inmersivas donde una barra roba espacio a lo que el productor está mirando.
    val showBottomBar = BOTTOM_DESTINATIONS.any { destination ->
        currentRoute?.hierarchy?.any { it.route == destination.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BOTTOM_DESTINATIONS.forEach { destination ->
                        val selected = currentRoute?.hierarchy
                            ?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    // Sin esto, alternar entre pestañas apila
                                    // destinos indefinidamente y el botón atrás
                                    // recorre todo el historial de pestañeo.
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AgroVisionDestinations.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(AgroVisionDestinations.HOME) {
                val viewModel: HomeViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                HomeScreen(
                    pendingCount = state.pendingCount,
                    catalogCount = state.catalogCount,
                    onAnalyze = { navController.navigate(AgroVisionDestinations.SCAN) },
                    onCatalog = { navController.navigate(AgroVisionDestinations.CATALOG) },
                    onHistory = { navController.navigate(AgroVisionDestinations.HISTORY) },
                )
            }

            composable(AgroVisionDestinations.SCAN) {
                ScanRoute(
                    onPestDetail = { navController.navigate(AgroVisionDestinations.pestDetail(it)) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(AgroVisionDestinations.CATALOG) {
                val viewModel: CatalogViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                CatalogScreen(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onPestClick = {
                        navController.navigate(AgroVisionDestinations.pestDetail(it))
                    },
                )
            }

            composable(
                route = AgroVisionDestinations.PEST_DETAIL,
                arguments = listOf(navArgument("pestId") { type = NavType.StringType }),
            ) {
                val viewModel: PestDetailViewModel = hiltViewModel()
                val pest by viewModel.pest.collectAsStateWithLifecycle()

                PestDetailScreen(pest = pest, onBack = { navController.popBackStack() })
            }

            composable(AgroVisionDestinations.HISTORY) {
                // ⚠️ Pendiente. Se declara la ruta para que la barra inferior
                // tenga tres destinos reales desde ya; la pantalla llega después.
                HistoryPlaceholder()
            }
        }
    }
}
