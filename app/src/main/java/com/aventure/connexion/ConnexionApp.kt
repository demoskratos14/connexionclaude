package com.aventure.connexion

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private enum class Screen { HOME, SETUP, BROWSER }

@Composable
fun ConnexionApp() {
    val context = LocalContext.current
    var credentials by remember { mutableStateOf(SecretStore.load(context)) }
    var screen by remember {
        mutableStateOf(if (credentials == null) Screen.SETUP else Screen.HOME)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (screen) {
            Screen.SETUP -> SetupScreen(
                initial = credentials,
                canCancel = credentials != null,
                onSaved = { saved ->
                    SecretStore.save(context, saved)
                    credentials = saved
                    screen = Screen.HOME
                },
                onCancel = { screen = Screen.HOME },
            )

            Screen.HOME -> HomeScreen(
                email = credentials?.email.orEmpty(),
                onConnect = { screen = Screen.BROWSER },
                onEdit = { screen = Screen.SETUP },
            )

            Screen.BROWSER -> credentials?.let { saved ->
                BrowserScreen(credentials = saved, onClose = { screen = Screen.HOME })
            }
        }
    }
}

@Composable
private fun HomeScreen(email: String, onConnect: () -> Unit, onEdit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = email, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.home_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connect))
        }
        TextButton(onClick = onEdit) {
            Text(stringResource(R.string.edit))
        }
    }
}

@Composable
private fun SetupScreen(
    initial: Credentials?,
    canCancel: Boolean,
    onSaved: (Credentials) -> Unit,
    onCancel: () -> Unit,
) {
    var email by remember { mutableStateOf(initial?.email.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(text = stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.setup_note), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.email_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password_label)) },
            singleLine = true,
            visualTransformation = if (showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSaved(Credentials(email.trim(), password)) },
            enabled = email.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.save))
        }
        if (canCancel) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun BrowserScreen(credentials: Credentials, onClose: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(0) }
    val session = remember {
        WebSession(context, credentials, onClaudeLink = { selected = 0 })
    }

    // Démarre la session à l'affichage ; à la fermeture (bouton, retour ou autre écran), tout est effacé.
    DisposableEffect(session) {
        session.start()
        onDispose { session.close() }
    }

    // Retour : d'abord dans l'historique de l'onglet affiché, puis on ferme la session.
    BackHandler {
        val current = if (selected == 0) session.claudeView else session.mailView
        if (current.canGoBack()) current.goBack() else onClose()
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryTabRow(selectedTabIndex = selected, modifier = Modifier.weight(1f)) {
                Tab(
                    selected = selected == 0,
                    onClick = { selected = 0 },
                    text = { Text(stringResource(R.string.tab_claude)) },
                )
                Tab(
                    selected = selected == 1,
                    onClick = { selected = 1 },
                    text = { Text(stringResource(R.string.tab_mail)) },
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { session.claudeView },
                modifier = Modifier.fillMaxSize(),
                update = { it.visibility = if (selected == 0) View.VISIBLE else View.INVISIBLE },
            )
            AndroidView(
                factory = { session.mailView },
                modifier = Modifier.fillMaxSize(),
                update = {
                    it.visibility = if (selected == 1) View.VISIBLE else View.INVISIBLE
                    // Le chargement attend que la vue soit affichée, donc qu'elle ait sa vraie taille.
                    if (selected == 1) it.post { session.openMail() }
                },
            )
            // DIAGNOSTIC TEMPORAIRE (à retirer une fois le problème de Proton Mail résolu).
            if (selected == 1 && session.debug.isNotEmpty()) {
                Text(
                    text = session.debug,
                    color = Color.Black,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(0.75f)
                        .background(Color(0xDDFFFFFF)),
                )
            }
        }
    }
}
