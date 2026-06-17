package com.pda.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pda.app.data.prefs.UserPreferences
import com.pda.app.ui.batchdetail.BatchDetailScreen
import com.pda.app.ui.dockreceiving.DockReceivingScreen
import com.pda.app.ui.home.HomeScreen
import com.pda.app.ui.i18n.AppLanguage
import com.pda.app.ui.i18n.LocalAppLanguage
import com.pda.app.ui.i18n.LocalAppStrings
import com.pda.app.ui.receivereport.ReceiveReportScreen
import com.pda.app.ui.login.LoginScreen
import com.pda.app.ui.theme.PdaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Read-only here: MainActivity only observes the language; ViewModels own the write path. */
    @Inject lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val langName by userPreferences.appLanguage.collectAsStateWithLifecycle(initialValue = null)
            val language = AppLanguage.fromName(langName)
            CompositionLocalProvider(
                LocalAppStrings provides language.strings,
                LocalAppLanguage provides language
            ) {
            PdaTheme {
                val navController = rememberNavController()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "login",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("login") {
                            LoginScreen(
                                onLoginSuccess = {
                                    navController.navigate("home") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("home") {
                            HomeScreen(
                                onLogout = {
                                    navController.navigate("login") {
                                        popUpTo("home") { inclusive = true }
                                    }
                                },
                                onNavigateToDockReceiving = { warehouseId ->
                                    navController.navigate("dock-receiving/$warehouseId")
                                },
                                onNavigateToReceiveReport = { warehouseId ->
                                    navController.navigate("receive-report/$warehouseId")
                                }
                            )
                        }
                        composable(
                            route = "dock-receiving/{warehouseId}",
                            arguments = listOf(navArgument("warehouseId") { type = NavType.StringType })
                        ) {
                            DockReceivingScreen(onBack = { navController.popBackStack() })
                        }
                        composable(
                            route = "receive-report/{warehouseId}",
                            arguments = listOf(navArgument("warehouseId") { type = NavType.StringType })
                        ) {
                            ReceiveReportScreen(
                                onBack = { navController.popBackStack() },
                                onOpenBatch = { batchId, batchNumber ->
                                    navController.navigate("batch-detail/$batchId/${Uri.encode(batchNumber)}")
                                }
                            )
                        }
                        composable(
                            route = "batch-detail/{batchId}/{batchNumber}",
                            arguments = listOf(
                                navArgument("batchId") { type = NavType.StringType },
                                navArgument("batchNumber") { type = NavType.StringType }
                            )
                        ) {
                            BatchDetailScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
            }
        }
    }
}
