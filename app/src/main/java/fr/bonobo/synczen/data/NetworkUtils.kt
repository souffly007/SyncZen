package fr.bonobo.synczen.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Sur la plupart des téléphones, les données mobiles restent actives en plus
 * du Wi-Fi. Android choisit alors par défaut le réseau qui a un accès
 * Internet validé pour toutes les connexions de l'app — souvent la 4G/5G —
 * même quand le Wi-Fi est connecté. Un NAS local n'étant joignable QUE via
 * le Wi-Fi, toute connexion SMB/WebDAV part alors sur le mauvais réseau et
 * échoue systématiquement (pas de façon intermittente).
 *
 * On force donc explicitement le process à utiliser le réseau Wi-Fi actif
 * avant chaque tentative de connexion au NAS.
 */
object NetworkUtils {

    /**
     * Lie le process courant au réseau Wi-Fi actuellement connecté, s'il y en
     * a un. Renvoie true si la liaison a réussi (ou si elle était déjà en
     * place), false s'il n'y a aucun Wi-Fi actif — dans ce cas, la
     * synchronisation ne devrait de toute façon pas avoir lieu.
     */
    fun bindToWifiIfAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        val wifiNetwork = cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: return false

        return cm.bindProcessToNetwork(wifiNetwork)
    }

    /** À appeler si on veut explicitement rendre la main au routage par défaut du système. */
    fun unbind(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        cm.bindProcessToNetwork(null)
    }
}
