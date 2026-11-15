package com.aventure.connexion

import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Date
import java.util.UUID

private enum class Screen { HOME, SETUP, BROWSER }

@Composable
fun ConnexionApp() {
    val context = LocalContext.current
    var accounts by remember { mutableStateOf(SecretStore.loadAccounts(context)) }
    var screen by remember {
        mutableStateOf(if (accounts.isEmpty()) Screen.SETUP else Screen.HOME)
    }
    // Adresse en cours de modification (null = nouvelle adresse) et adresse utilisée pour se connecter.
    var editing by remember { mutableStateOf<Account?>(null) }
    var active by remember { mutableStateOf<Account?>(null) }

    fun updateAccounts(list: List<Account>) {
        SecretStore.saveAccounts(context, list)
        accounts = list
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (screen) {
            // key : l'écran repart de zéro quand on passe d'une adresse à une autre.
            Screen.SETUP -> key(editing?.id) {
                SetupScreen(
                    initial = editing,
                    canCancel = accounts.isNotEmpty(),
                    onSaved = { saved ->
                        val exists = accounts.any { it.id == saved.id }
                        updateAccounts(
                            if (exists) accounts.map { if (it.id == saved.id) saved else it } else accounts + saved,
                        )
                        screen = Screen.HOME
                    },
                    onDelete = { removed ->
                        val rest = accounts.filterNot { it.id == removed.id }
                        updateAccounts(rest)
                        editing = null
                        screen = if (rest.isEmpty()) Screen.SETUP else Screen.HOME
                    },
                    onCancel = { screen = Screen.HOME },
                )
            }

            Screen.HOME -> HomeScreen(
                accounts = accounts,
                onConnect = { account ->
                    // On note l'heure de cette connexion ; elle s'affiche sur la ligne de l'adresse.
                    val connected = account.copy(lastLogin = System.currentTimeMillis())
                    updateAccounts(accounts.map { if (it.id == connected.id) connected else it })
                    active = connected
                    screen = Screen.BROWSER
                },
                onEdit = { account ->
                    editing = account
                    screen = Screen.SETUP
                },
                onAdd = {
                    editing = null
                    screen = Screen.SETUP
                },
            )

            Screen.BROWSER -> active?.let { account ->
                BrowserScreen(
                    credentials = Credentials(account.email, account.password),
                    onClose = { screen = Screen.HOME },
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    accounts: List<Account>,
    onConnect: (Account) -> Unit,
    onEdit: (Account) -> Unit,
    onAdd: () -> Unit,
) {
    val context = LocalContext.current
    // Mode « modifier » : activé par le bouton du bas, un clic sur une adresse l'ouvre alors en modification.
    var editMode by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(if (editMode) R.string.edit_hint else R.string.home_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(accounts, key = { it.id }) { account ->
                Card(
                    onClick = { if (editMode) onEdit(account) else onConnect(account) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (editMode) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        CardDefaults.cardColors()
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = account.email, style = MaterialTheme.typography.titleMedium)
                            val recent = account.lastLogin > 0 &&
                                System.currentTimeMillis() - account.lastLogin < SecretStore.RECENT_LOGIN_MS
                            if (recent) {
                                val time = DateFormat.getTimeFormat(context).format(Date(account.lastLogin))
                                val label = if (DateUtils.isToday(account.lastLogin)) {
                                    stringResource(R.string.last_login_today, time)
                                } else {
                                    stringResource(R.string.last_login_yesterday, time)
                                }
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (account.note.isNotBlank()) {
                            Text(
                                text = account.note,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    editMode = false
                    onAdd()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.add_account))
            }
            OutlinedButton(onClick = { editMode = !editMode }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (editMode) R.string.edit_cancel else R.string.edit_account))
            }
        }
    }
}

@Composable
private fun SetupScreen(
    initial: Account?,
    canCancel: Boolean,
    onSaved: (Account) -> Unit,
    onDelete: (Account) -> Unit,
    onCancel: () -> Unit,
) {
    var email by remember { mutableStateOf(initial?.email.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var showPassword by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(SecretStore.MAX_NOTE_LENGTH) },
            label = { Text(stringResource(R.string.note_label)) },
            supportingText = { Text("${note.length}/${SecretStore.MAX_NOTE_LENGTH}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onSaved(
                    Account(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        email = email.trim(),
                        password = password,
                        lastLogin = initial?.lastLogin ?: 0L,
                        note = note.trim(),
                    ),
                )
            },
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
        if (initial != null) {
            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.delete_account))
            }
        }
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(initial)
                    },
                ) {
                    Text(stringResource(R.string.confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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
        }
    }
}
