package com.castla.mirror.network

import com.castla.mirror.diagnostics.DiagnosticEvent
import com.castla.mirror.diagnostics.DiagnosticSanitizer
import com.castla.mirror.diagnostics.MirrorDiagnostics

/**
 * Emits URL_SELECTED with the full candidate snapshot so a wrong priority pick
 * can be traced from a shared log. Deduplicates on the selected IP so routine
 * rescans don't spam; session start must pass force=true because
 * FileLogger.clear() wipes the file but not this in-memory state.
 */
object UrlSelectionLogger {

    @Volatile private var lastLoggedIp: String? = null

    fun log(selected: IpCandidate?, candidates: List<IpCandidate>, force: Boolean = false) {
        val ip = selected?.ip ?: "none"
        if (!force && ip == lastLoggedIp) return
        lastLoggedIp = ip
        val snapshot = candidates.joinToString(",") {
            "${it.iface}/${DiagnosticSanitizer.maskIp(it.ip)}/p${it.priority}"
        }
        MirrorDiagnostics.log(
            DiagnosticEvent.URL_SELECTED,
            "ip=${if (selected != null) DiagnosticSanitizer.maskIp(selected.ip) else "none"} " +
                "iface=${selected?.iface ?: "none"} candidates=[$snapshot]"
        )
    }
}
