package com.aeroglyph.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aeroglyph.app.cloud.UserProfile
import com.aeroglyph.app.ui.components.DataLabel
import com.aeroglyph.app.ui.components.DataValue
import com.aeroglyph.app.ui.components.Panel
import com.aeroglyph.app.ui.components.ScreenHeading
import com.aeroglyph.app.ui.components.SecondaryButton
import com.aeroglyph.app.ui.components.StatusPill
import com.aeroglyph.app.ui.components.WarningBanner
import com.aeroglyph.app.ui.theme.DataType
import com.aeroglyph.app.ui.theme.LocalAeroglyphExtras

/**
 * User management for admins.
 *
 * Every control here is a request the server is free to refuse: the buttons
 * are hidden from non-admins for tidiness, but the actual enforcement is
 * row-level security in Postgres. A client that decides who is allowed to do
 * what is not a permission system -- anyone can edit a client.
 */
@Composable
fun AdminScreen(
    users: List<UserProfile>,
    currentUserId: String,
    busy: Boolean,
    error: String?,
    onSetRole: (UserProfile, String) -> Unit,
    onSetDisabled: (UserProfile, Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    val extras = LocalAeroglyphExtras.current

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeading(title = "Users", caption = "ADMINISTRATION")
        Spacer(Modifier.height(14.dp))

        if (error != null) {
            WarningBanner(message = error, onDismiss = onDismissError)
            Spacer(Modifier.height(14.dp))
        }

        if (users.isEmpty()) {
            Panel {
                DataLabel(if (busy) "LOADING" else "NO USERS")
                Spacer(Modifier.height(8.dp))
                Text(
                    if (busy) {
                        "Fetching the user list…"
                    } else {
                        "Nobody has registered yet, or your account doesn't have permission to " +
                            "list users. Access is enforced by the database, not by this screen."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(users, key = { it.id }) { user ->
                val isSelf = user.id == currentUserId
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DataValue(user.email, modifier = Modifier.weight(1f))
                        StatusPill(
                            text = if (user.isAdmin) "ADMIN" else "USER",
                            tint = if (user.isAdmin) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                extras.textTertiary
                            },
                        )
                    }

                    if (user.disabled) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Account disabled",
                            style = DataType.small,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    if (isSelf) {
                        // Removing your own admin rights, or disabling yourself,
                        // can leave a deployment with nobody able to administer
                        // it -- and no way back from inside the app.
                        Text(
                            "This is you. Change your own role from another admin account.",
                            style = DataType.small,
                            color = extras.textTertiary,
                        )
                    } else {
                        Row(Modifier.fillMaxWidth()) {
                            SecondaryButton(
                                label = if (user.isAdmin) "Make user" else "Make admin",
                                onClick = {
                                    if (!busy) {
                                        onSetRole(
                                            user,
                                            if (user.isAdmin) UserProfile.ROLE_USER else UserProfile.ROLE_ADMIN,
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            SecondaryButton(
                                label = if (user.disabled) "Enable" else "Disable",
                                onClick = { if (!busy) onSetDisabled(user, !user.disabled) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
