package com.jywr.pcbuapk.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jywr.pcbuapk.ui.screens.MainScreen
import com.jywr.pcbuapk.ui.screens.PairingScreen
import com.jywr.pcbuapk.ui.screens.SettingsScreen

/**
 * 应用导航
 *
 * 解锁请求通过 MainActivity.pendingUnlock（StateFlow）传递，
 * 不再通过导航参数传入，这样 onNewIntent 也能正常触发验证弹窗。
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "main"
    ) {
        // 主界面
        composable("main") {
            MainScreen(
                onNavigateToPairing = {
                    navController.navigate("pairing")
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }

        // 配对界面
        composable("pairing") {
            PairingScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // 设置界面
        composable("settings") {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
