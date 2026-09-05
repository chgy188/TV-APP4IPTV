@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.example.composedtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.composedtv.viewmodel.PlayerViewModel

/**
 * 登录/注册界面
 *
 * 使用 Material3 Surface + clickable，兼容触摸点击和 D-pad 焦点。
 */
@Composable
fun LoginScreen(
    vm: PlayerViewModel,
    lastLoginUsername: String?,
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit
) {
    // 进入登录界面时，若 lastLoginUsername 非空，直接预填到用户名输入框
    var username by remember(lastLoginUsername) { mutableStateOf(lastLoginUsername ?: "") }
    var password by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // 密码框聚焦请求器：选中上次用户名后自动聚焦到密码框
    val passwordFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // 已记住用户名时默认收起用户名输入框（仅输密码即可），腾出垂直空间，
    // 避免 TV 端弹出系统键盘时遮挡底部登录按钮；需切换账号时用"使用其他账号"展开。
    var editingUser by remember(lastLoginUsername) { mutableStateOf(lastLoginUsername.isNullOrEmpty()) }
    val userFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0F1117), Color(0xFF1A1D27))
                )
            )
    ) {
        // 屏幕较矮（手机横屏）时启用紧凑布局，尽量一屏显示
        val compact = maxHeight < 520.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(
                    horizontal = if (compact) 20.dp else 24.dp,
                    vertical = if (compact) 16.dp else 32.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                BackButton(onClick = onBack)
            }

            if (!compact) Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isRegister) "注册" else "登录",
                fontSize = if (compact) 24.sp else 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // 用户名已预填时，自动聚焦密码框（用户直接输密码即可）
            androidx.compose.runtime.LaunchedEffect(lastLoginUsername) {
                if (!lastLoginUsername.isNullOrEmpty()) {
                    kotlinx.coroutines.delay(120)
                    runCatching { passwordFocusRequester.requestFocus() }
                }
            }

            val showUserField = isRegister || editingUser || lastLoginUsername.isNullOrEmpty()
            if (showUserField) {
                InputField(
                    value = username,
                    onValueChange = { username = it },
                    label = "用户名",
                    icon = Icons.Default.Person,
                    isPassword = false,
                    compact = compact,
                    modifier = Modifier.focusRequester(userFocusRequester),
                    onEnter = { passwordFocusRequester.requestFocus() }
                )
            } else {
                // 已记住账号：仅以只读感展示用户名 + "使用其他账号"入口，节省垂直空间
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editingUser = true
                            username = ""
                        }
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = lastLoginUsername ?: "",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "使用其他账号",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 展开用户名输入框（切换账号）后自动聚焦，便于直接键入
            androidx.compose.runtime.LaunchedEffect(editingUser) {
                if (editingUser && lastLoginUsername != null) {
                    kotlinx.coroutines.delay(120)
                    runCatching { userFocusRequester.requestFocus() }
                }
            }

            InputField(
                value = password,
                onValueChange = { password = it },
                label = "密码",
                icon = Icons.Default.Lock,
                isPassword = !showPassword,
                compact = compact,
                modifier = Modifier.focusRequester(passwordFocusRequester),
                onEnter = {
                    if (username.isNotBlank() && password.isNotBlank() && !isLoading) {
                        isLoading = true
                        errorMsg = null
                        vm.login(username, password, isRegister) { ok, msg ->
                            isLoading = false
                            if (ok) onLoginSuccess() else errorMsg = msg ?: "操作失败"
                        }
                    }
                },
                trailingIcon = {
                    Box(
                        modifier = Modifier
                            .clickable { showPassword = !showPassword }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "显示/隐藏密码",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )

            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ActionButton(
                        text = if (isRegister) "注册" else "登录",
                        primary = true,
                        compact = compact,
                        onClick = {
                            if (username.isBlank() || password.isBlank()) {
                                errorMsg = "请输入用户名和密码"
                                return@ActionButton
                            }
                            isLoading = true
                            errorMsg = null
                            vm.login(username, password, isRegister) { ok, msg ->
                                isLoading = false
                                if (ok) {
                                    onLoginSuccess()
                                } else {
                                    errorMsg = msg ?: "操作失败"
                                }
                            }
                        }
                    )
                    ActionButton(
                        text = if (isRegister) "已有账号？登录" else "没有账号？注册",
                        primary = false,
                        compact = compact,
                        onClick = {
                            isRegister = !isRegister
                            errorMsg = null
                        }
                    )
                }
            }

            // 遥控器操作说明（手机上隐藏，节省空间）
            if (!compact) {
                Spacer(modifier = Modifier.height(16.dp))

                RemoteHint(
                    items = listOf(
                        "←→↑↓" to "切换输入",
                        "确定" to "输入/提交",
                        "返回" to "上一页"
                    )
                )
                Text(
                    text = "提示：聚焦输入框按确定键调出软键盘，完成输入后按确定返回",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun RemoteHint(items: List<Pair<String, String>>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Color.Black.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (key, desc) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = key,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = desc,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        modifier = Modifier
            .scale(if (isFocused) 1.1f else 1.0f)
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(),
                onClick = onClick
            ),
        shape = RoundedCornerShape(8.dp),
        color = if (isFocused) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        contentColor = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface
            )
            Text("返回", color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    isPassword: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    trailingIcon: @Composable (() -> Unit)? = null,
    onEnter: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = if (compact) 13.sp else 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 46.dp else 52.dp)
                .background(
                    if (focused) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            fontSize = 16.sp
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused },
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        ),
                        visualTransformation = if (isPassword) PasswordVisualTransformation()
                        else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                            imeAction = if (onEnter != null) ImeAction.Done else ImeAction.Default
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { onEnter?.invoke() }
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
                if (trailingIcon != null) trailingIcon()
            }
        }
    }
}

@Composable
private fun ActionButton(text: String, primary: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        modifier = Modifier
            .scale(if (isFocused) 1.05f else 1.0f)
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(),
                onClick = onClick
            ),
        shape = RoundedCornerShape(8.dp),
        color = if (isFocused) {
            if (primary) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.primaryContainer
        } else {
            if (primary) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surface
        },
        contentColor = if (isFocused) {
            if (primary) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            if (primary) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
        }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = if (compact) 18.dp else 24.dp,
                vertical = if (compact) 8.dp else 12.dp
            ),
            fontSize = if (compact) 15.sp else 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
