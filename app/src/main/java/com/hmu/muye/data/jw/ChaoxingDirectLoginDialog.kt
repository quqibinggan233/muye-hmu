package com.hmu.muye.data.jw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fengkao.timetable.ui.theme.BrandGreen
import com.fengkao.timetable.ui.theme.CardWhite
import com.fengkao.timetable.ui.theme.InkGray
import com.fengkao.timetable.ui.theme.InkMid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 超星综合教学管理系统原生 API 登录弹窗：
 * 默认通过后端 `/admin/login` 接口直连登录，无需跳转浏览器或打开网页 WebView；
 * 仅当账号触发验证码或用户需要统一身份认证时，才可一键切换到备用网页登录。
 */
@Composable
fun ChaoxingDirectLoginDialog(
    baseUrl: String = ChaoxingJwStore.DEFAULT_JW_BASE,
    initialUsername: String = "",
    onDismiss: () -> Unit,
    onLoginSuccess: (username: String, passwordToSave: String?, cookies: String) -> Unit,
) {
    var useWebFallback by rememberSaveable { mutableStateOf(false) }

    if (useWebFallback) {
        ChaoxingWebLoginDialog(
            baseUrl = baseUrl,
            onDismiss = onDismiss,
            onCapturedCookies = { cookies ->
                onLoginSuccess(initialUsername.trim(), null, cookies)
            },
        )
        return
    }

    var username by rememberSaveable(initialUsername) { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var rememberPwd by rememberSaveable { mutableStateOf(true) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var errorMsg by rememberSaveable { mutableStateOf<String?>(null) }
    var recommendWeb by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = CardWhite,
        title = { Text("登录超星教务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "直接使用教务学号与密码通过接口登录（无需跳转网页），登录态与凭据仅加密保存在本机。",
                    fontSize = 12.sp,
                    color = InkGray,
                    lineHeight = 18.sp,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; errorMsg = null },
                    label = { Text("教务学号") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMsg = null },
                    label = { Text("教务密码") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = "显示/隐藏密码",
                                tint = InkGray,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { rememberPwd = !rememberPwd },
                ) {
                    Checkbox(
                        checked = rememberPwd,
                        onCheckedChange = { if (!busy) rememberPwd = it },
                        colors = CheckboxDefaults.colors(checkedColor = BrandGreen),
                    )
                    Text("加密记住密码（会话过期可自动续签）", fontSize = 12.sp, color = InkMid)
                }

                errorMsg?.let { msg ->
                    Text(
                        msg,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Button(
                    onClick = {
                        val cleanUser = username.trim()
                        if (cleanUser.isBlank() || password.isBlank()) {
                            errorMsg = "请输入教务学号和密码"
                            return@Button
                        }
                        busy = true
                        errorMsg = null
                        recommendWeb = false
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ChaoxingJwClient(baseUrl).loginWithPassword(cleanUser, password)
                            }
                            busy = false
                            when (result) {
                                is JwLoginResult.Success -> {
                                    onLoginSuccess(cleanUser, password.takeIf { rememberPwd }, result.cookies)
                                }
                                is JwLoginResult.InvalidCredentials -> {
                                    errorMsg = result.message
                                }
                                is JwLoginResult.NeedWebFallback -> {
                                    errorMsg = result.reason
                                    recommendWeb = true
                                }
                                is JwLoginResult.Error -> {
                                    errorMsg = result.message
                                    recommendWeb = true
                                }
                            }
                        }
                    },
                    enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("登录超星教务", fontWeight = FontWeight.SemiBold)
                    }
                }

                if (recommendWeb) {
                    OutlinedButton(
                        onClick = { useWebFallback = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("切换网页验证登录", color = BrandGreen, fontWeight = FontWeight.Medium)
                    }
                } else {
                    TextButton(
                        onClick = { useWebFallback = true },
                        enabled = !busy,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("遇到验证码或统一认证？切换网页登录", fontSize = 12.sp, color = InkGray)
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}
