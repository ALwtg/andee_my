package net.kuafuai.andee.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The window body: one photograph behind everything, the sidebar and the content
 * as glass on it, the approval and question cards above both. Its own function so
 * the screenshot harness draws exactly what the app draws.
 */
@Composable
fun AppRoot(nav: Nav, pending: ApprovalGate.Pending?, ask: AskGate.Pending?) {
    Box(Modifier.fillMaxSize()) {
        BackdropLayer()
        Row(Modifier.fillMaxSize()) {
            Sidebar(nav)
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                // A reading column: on a wide window the conversation stays a comfortable measure.
                val screen = nav.current
                val card = if (screen == "chat") Modifier else Modifier.padding(14.dp).frostedCard()
                Box(Modifier.widthIn(max = 900.dp).fillMaxHeight().then(card)) {
                    when {
                        screen == "settings" -> SettingsScreen(
                            onDone = { nav.back() },
                            onOpenNotebook = { nav.push("notebook") },
                            onOpenVault = { nav.push("vault") },
                            onOpenScenes = { nav.push("scenes") },
                            onOpenExpressions = { nav.push("expressions") },
                            onOpenSelfCheck = { nav.push("selfcheck") },
                        )
                        screen == "selfcheck" -> SelfCheckCard(onDone = { nav.back() }, onOpenSettings = { nav.push("settings") })
                        screen == "notebook" -> NotebookCard(onDone = { nav.back() })
                        screen == "vault" -> VaultCard(onDone = { nav.back() })
                        screen == "scenes" -> ScenesCard(onDone = { nav.back() })
                        screen == "expressions" -> ExpressionsCard(onDone = { nav.back() })
                        screen == "artifacts" -> ArtifactsCard(onDone = { nav.back() })
                        screen.startsWith("topic:") -> TopicScreen(screen.removePrefix("topic:").toLongOrNull() ?: -1L, onDone = { nav.back() })
                        else -> ChatScreen(onOpenArtifacts = { nav.go("artifacts") })
                    }
                }
            }
        }
        pending?.let { ApprovalCard(it) }
        ask?.let { AskCard(it) }
    }
}
