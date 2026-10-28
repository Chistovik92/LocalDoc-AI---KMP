package com.localdoc.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.platform.CertificateProblem
import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.cancel
import com.localdoc.ai.resources.cert_dialog_text
import com.localdoc.ai.resources.cert_dialog_title
import com.localdoc.ai.resources.cert_fingerprint
import com.localdoc.ai.resources.cert_problem_expired
import com.localdoc.ai.resources.cert_problem_hostname
import com.localdoc.ai.resources.cert_problem_not_yet_valid
import com.localdoc.ai.resources.cert_problem_other
import com.localdoc.ai.resources.cert_problem_unknown_issuer
import com.localdoc.ai.resources.cert_trust_and_open
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Окно «Сертификат сайта не проверен»: сайт открывается только после согласия пользователя. Согласие запоминается
 * вместе с отпечатком сертификата (при его смене приложение спросит снова); общая галочка есть в настройках.
 */
@Composable
fun WebCertificateHost() {
    val request by AppGraph.webSession.certificateRequest.collectAsState()
    val current = request ?: return
    AlertDialog(
        onDismissRequest = { AppGraph.webSession.dismissCertificate() },
        title = { Text(stringResource(Res.string.cert_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(Res.string.cert_dialog_text, current.host, stringResource(problemLabel(current.problem))),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(Res.string.cert_fingerprint, current.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { AppGraph.webSession.acceptCertificate() }) {
                Text(stringResource(Res.string.cert_trust_and_open), maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            TextButton(onClick = { AppGraph.webSession.dismissCertificate() }) {
                Text(stringResource(Res.string.cancel), maxLines = 1, softWrap = false)
            }
        }
    )
}

private fun problemLabel(problem: CertificateProblem): StringResource = when (problem) {
    CertificateProblem.UNKNOWN_ISSUER -> Res.string.cert_problem_unknown_issuer
    CertificateProblem.EXPIRED -> Res.string.cert_problem_expired
    CertificateProblem.NOT_YET_VALID -> Res.string.cert_problem_not_yet_valid
    CertificateProblem.HOSTNAME_MISMATCH -> Res.string.cert_problem_hostname
    CertificateProblem.OTHER -> Res.string.cert_problem_other
}
