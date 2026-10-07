package com.jywr.pcbuapk.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jywr.pcbuapk.MainActivity
import com.jywr.pcbuapk.ui.screens.MainScreen
import com.jywr.pcbuapk.ui.screens.PairingScreen
import com.jywr.pcbuapk.ui.screens.SettingsScreen

/** 路由名集中在这里，避免各处散落字符串字面量 */
private const val ROUTE_MAIN = "main"
private const val ROUTE_PAIRING = "pairing"
private const val ROUTE_SETTINGS = "settings"

/**
 * 应用导航
 *
 * 解锁请求通过 [MainActivity.pendingUnlock]（Channel 驱动的 Flow）传递，
 * 不经过导航参数，这样 onNewIntent 也能正常触发验证弹窗。
 *
 * 另外会收集 [MainActivity.navigateHome]：解锁请求可能在任何页面到达，
 * 而验证界面在主页，不主动切回去的话请求只能排队等用户自己返回（电脑端早就超时了）。
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    LaunchedEffect(Unit) {
        MainActivity.navigateHome.collect {
            val current = navController.currentDestination?.route
            if (current != ROUTE_MAIN) {
                Log.i("AppNavigation", "收到解锁请求，从 $current 切回主页")
                // popUpTo 主页 + inclusive=false：把主页之上的设置/配对页出栈，
                // 保证返回键行为正常，也不会重复堆栈
                navController.navigate(ROUTE_MAIN) {
                    popUpTo(ROUTE_MAIN) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_MAIN
    ) {
        // 主界面
        composable(ROUTE_MAIN) {
            MainScreen(
                onNavigateToPairing = {
                    navController.navigate(ROUTE_PAIRING)
                },
                onNavigateToSettings = {
                    navController.navigate(ROUTE_SETTINGS)
                }
            )
        }

        // 配对界面
        composable(ROUTE_PAIRING) {
            PairingScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // 设置界面
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
