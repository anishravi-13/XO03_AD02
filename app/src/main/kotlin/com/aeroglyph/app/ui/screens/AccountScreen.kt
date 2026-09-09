package com.aeroglyph.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aeroglyph.app.cloud.AccountState
import com.aeroglyph.app.cloud.UserProfile
import com.aeroglyph.app.ui.components.DataLabel
import com.aeroglyph.app.ui.components.DataValue
import com.aeroglyph.app.ui.components.GradientButton
import com.aeroglyph.app.ui.components.Panel
import com.aeroglyph.app.ui.components.ScreenHeading
import com.aeroglyph.app.ui.components.SecondaryButton
import com.aeroglyph.app.ui.components.StatusPill
import com.aeroglyph.app.ui.components.WarningBanner
import com.aeroglyph.app.ui.theme.DataType
import com.aeroglyph.app.ui.theme.LocalAeroglyphExtras

/**
 * Optional sign-in.
 *
 * The framing matters and is deliberate throughout the copy: broadcasting and
 * listening never require an account, so this screen explains what an account
 * *adds* rather than demanding one. An app built for rooms with no network
 * cannot put a login in front of its own purpose.
 */
@Composable
fun AccountScreen(
    state: AccountState,
    busy: Boolean,
    error: String?,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onDismissError: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    val extras = LocalAeroglyphExtras.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeading(
            title = "Account",
            caption = "OPTIONAL · WORKS WITHOUT ONE",
        )
        Spacer(Modifier.height(16.dp))

        if (error != null) {
            WarningBanner(message = error, onDismiss = onDismissError)
            Spacer(Modifier.height(16.dp))
        }

        when (state) {
            AccountState.NotConfigured -> Panel {
                DataLabel("CLOUD SYNC")
                Spacer(Modifier.height(10.dp))
                Text(
                    "No Supabase project is configured in this build, so message history and the " +
                        "admin view are switched off. Everything acoustic works exactly as it does " +
                        "otherwise — none of it depends on a network.\n\n" +
                        "To enable it, add supabase.url and supabase.anonKey to local.properties and " +
                        "rebuild. The README has the table schema and access policies.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AccountState.SignedOut -> Panel {
                DataLabel("SIGN IN")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Signing in keeps a copy of what you send and receive, so a log survives " +
                        "reinstalling the app or swapping devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(18.dp))

                val ready = email.isNotBlank() && password.length >= 6 && !busy
                GradientButton(
                    label = if (busy) "Working…" else "Sign in",
                    onClick = { onSignIn(email, password) },
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(
                    label = "Create an account",
                    onClick = { if (ready) onSignUp(email, password) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Passwords must be at least 6 characters.",
                    style = DataType.small,
                    color = extras.textTertiary,
                )
            }

            is AccountState.SignedIn -> {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DataLabel("SIGNED IN")
                        Spacer(Modifier.weight(1f))
                        StatusPill(
                            text = if (state.profile.isAdmin) "ADMIN" else "USER",
                            tint = if (state.profile.isAdmin) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                extras.textTertiary
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DataValue(state.profile.email)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Messages you send and receive are now saved to your account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(visible = state.profile.isAdmin) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        Panel {
                            DataLabel("ADMINISTRATION")
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Manage who can use this deployment: promote or demote admins, " +
                                    "and disable accounts.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(14.dp))
                            GradientButton(
                                label = "Manage users",
                                onClick = onOpenAdmin,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    label = "Sign out",
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}
