package com.example.composedtv

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.composedtv.data.remote.ApiClient
import com.example.composedtv.ui.screens.LoginScreen
import com.example.composedtv.ui.screens.PlayerScreen
import com.example.composedtv.ui.screens.UserSelectionScreen
import com.example.composedtv.ui.theme.ComposedTVTheme
import com.example.composedtv.viewmodel.PlayerViewModel

/**
 * 屏幕导航状态
 */
sealed class Screen {
    object UserSelection : Screen()
    object Login : Screen()
    data class Player(val isGuest: Boolean) : Screen()
}

class MainActivity : ComponentActivity() {

    // 通过 Activity viewModels() 获取 ViewModel 实例，供 dispatchKeyEvent 兜底使用
    private val vm: PlayerViewModel by viewModels()

    // 当前屏幕引用（在 Composable 中同步更新），用于 dispatchKeyEvent 判断是否处于 Player 界面
    private var currentScreenRef: Screen = Screen.UserSelection
    private var lastBackPressTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 捕获 Activity 引用，供下方 Compose 树（经 AppContent 间接渲染）回调退出 APP
        val self = this

        // 双击返回退出（仅在播放器界面生效）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000L) {
                    finishAffinity()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(this@MainActivity, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
                }
            }
        })

        setContent {
            ComposedTVTheme {
                AppContent(
                    viewModel = vm,
                    onScreenChanged = { currentScreenRef = it },
                    onExitApp = { self.finishAffinity() }
                )
            }
        }
    }

    /**
     * 兜底按键处理：Activity 层 dispatchKeyEvent 能保证接收所有按键事件，
     * 即使 Compose 层因为焦点或 PlayerView 拦截导致确定键丢失，这里仍能捕获。
     * 仅在 Player 界面且侧边栏未显示时处理确定键，避免与侧边栏内部的确定键（选择频道）冲突。
     */
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null) {
            val current = currentScreenRef
            if (current is Screen.Player) {
                val uiState = vm.uiState.value
                // 侧边栏关闭时，拦截左右键的 DOWN 和 UP（防止系统焦点导航抢键）
                if (!uiState.sidePanelVisible) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            // 设置抽屉打开时：左右键需在「左排分类 ↔ 右排选项」间导航，
                            // 不能拦截，否则既无法移动焦点，右键还会被误判为重载。
                            if (uiState.settingsVisible) return super.dispatchKeyEvent(event)
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                Log.d("MainActivity", "左键 → 发送收藏信号")
                                vm.sendToggleFavorite()
                            }
                            return true // 拦截 DOWN + UP，防止系统焦点导航抢键
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            // 同上：设置抽屉打开时不拦截，交由 Compose 焦点导航处理
                            if (uiState.settingsVisible) return super.dispatchKeyEvent(event)
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                Log.d("MainActivity", "右键 → 发送重载信号")
                                vm.sendManualReload()
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_MENU -> {
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                Log.d("MainActivity", "菜单键 → 切换设置抽屉")
                                vm.toggleSettingsDrawer()
                            }
                            return true
                        }
                    }
                    // 确定键兜底：仅在侧边栏和设置抽屉都未显示时，才弹出左侧节目单。
                    // 设置抽屉打开时必须把确认键交给 Compose 焦点项（确认配置选项），不能拦截。
                    if (event.action == KeyEvent.ACTION_DOWN && !uiState.settingsVisible) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                vm.toggleSidePanelFromActivity()
                                return true
                            }
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun AppContent(
    viewModel: PlayerViewModel,
    onScreenChanged: (Screen) -> Unit,
    onExitApp: () -> Unit
) {
    var screen by remember { mutableStateOf<Screen>(Screen.UserSelection) }
    val vm = viewModel
    val state by vm.uiState.collectAsState()

    // 屏幕切换时同步到 Activity，供 dispatchKeyEvent 兜底判断使用
    LaunchedEffect(screen) {
        onScreenChanged(screen)
    }

    when (val s = screen) {
        is Screen.UserSelection -> {
            UserSelectionScreen(
                storedUsers = state.storedUsers,
                onSelectUser = { username ->
                    // 不再免密直登：跳转登录界面并预填用户名，仍需输入密码
                    vm.prepareLoginWithUsername(username)
                    screen = Screen.Login
                },
                onSelectGuest = {
                    vm.enterAsGuest()
                    screen = Screen.Player(isGuest = true)
                },
                onSelectLogin = {
                    vm.prepareLoginWithUsername(null)
                    screen = Screen.Login
                }
            )
        }

        is Screen.Login -> {
            LoginScreen(
                vm = vm,
                lastLoginUsername = state.lastLoginUsername,
                onLoginSuccess = {
                    screen = Screen.Player(isGuest = false)
                },
                onBack = {
                    screen = Screen.UserSelection
                }
            )
        }

        is Screen.Player -> {
            PlayerScreen(
                vm = vm,
                isGuest = s.isGuest,
                onExit = {
                    // 返回用户选择界面
                    screen = Screen.UserSelection
                },
                onExitApp = onExitApp
            )
        }
    }
}
