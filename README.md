# SyncZen

Application Android de sauvegarde locale automatique (Wi-Fi / NAS). Kotlin 100%, Jetpack Compose, MVVM, WorkManager,
Room, OkHttp (WebDAV) + JCIFS-NG (SMB). Licence GPL v3 (voir `LICENSE`).

[![Version](https://img.shields.io/badge/version-0.3.0-blue.svg)](CHANGELOG.md)
[![Licence](https://img.shields.io/badge/licence-GPL_v3-green.svg)](LICENSE)

## Pour démarrer
1. Ouvrir le dossier dans Android Studio (Import project). Le wrapper Gradle
   n'est pas fourni (fichier binaire) — Android Studio te proposera de le
   générer automatiquement à l'ouverture, ou lance `gradle wrapper` en local
   si tu as Gradle installé.
2. Laisser Gradle synchroniser (JDK 17 requis).
3. L'icône SyncZen et l'interface serveur sont déjà intégrées. Tu peux les
   personnaliser dans `res/drawable/` si tu le souhaites.
4. Lancer sur un appareil réel (le SMB/WebDAV local ne fonctionne pas bien sur
   l'émulateur, qui n'est pas sur le même réseau Wi-Fi). Testé visé : Poco X7 Pro.

## Ce qui a changé depuis le premier jet
- **Sélection de dossiers en SAF** (Storage Access Framework) au lieu de
  cases à cocher DCIM/Pictures/Documents fixes : plus fiable avec le
  stockage scopé (API 29+), et ça évite de demander `READ_MEDIA_IMAGES` /
  `READ_EXTERNAL_STORAGE` — cohérent avec l'approche privacy-first de tes
  autres apps. `SyncWorker` parcourt maintenant les arbres SAF stockés en
  base via `DocumentFile`, `NasRepository.uploadFile` prend un `InputStream`
  au lieu d'un `File`.
- **`network_security_config.xml`** remplace `usesCleartextTraffic` en dur
  dans le manifeste (plus propre, permet de restreindre plus tard si besoin).
- **Base de données exclue des sauvegardes cloud/transfert d'appareil**
  (`backup_rules.xml` / `data_extraction_rules.xml`) : les identifiants du
  NAS ne partent pas dans un backup Google.
- **LICENSE** (GPL v3 complète), **.gitignore**, **proguard-rules.pro**
  ajoutés.
- Interface rafraîchie : palette bleu indigo, nouvelle icône serveur et
  illustration de fond discrète dans le bandeau de synchronisation.
- Un test unitaire d'exemple (`NasConfigTest`) pour démarrer la suite de
  tests.

## Points encore ouverts / à finir
- **Upload WebDAV en mémoire** : le fichier est chargé entièrement en RAM
  avant le PUT (OkHttp ne fait pas de streaming direct depuis un
  `InputStream` sans écrire une `RequestBody` custom) — **Résolu** : implémentation de `StreamingFileBody` pour le streaming.
- **Gestion des conflits / renommage** : si un fichier du même nom existe déjà
  côté NAS, le SMB/WebDAV écrasera ou échouera selon le serveur — **Résolu** : ajout de stratégies de résolution configurables (SKIP, OVERWRITE, RENAME).
- **Notification de progression** : `SyncWorker` n'affiche pas de
  notification foreground pendant la synchro — **Résolu** : notifications foreground avec progression implémentées.
- **Filtrage par SSID précis** (`wifiSsid` dans `NasConfig`) : le champ existe
  et est stocké, mais `SyncWorker` ne le vérifie pas encore — **Résolu** : filtrage par SSID implémenté avec vérification du réseau actuel.
- **Mot de passe stocké en clair dans Room** — **Résolu** : chiffrement AES-256-GCM avec Android Keystore implémenté.

## Nouvelles fonctionnalités
- ✅ **Chiffrement des credentials** : AES-256-GCM avec Android Keystore
- ✅ **Notifications foreground** : avec progression pendant la synchronisation
- ✅ **Filtrage par SSID** : synchronisation uniquement sur un réseau Wi-Fi spécifique
- ✅ **Gestion des conflits** : stratégies configurables (Sauter, Écraser, Renommer)
- ✅ **Minification activée** : ProGuard/R8 pour les builds de production
- ✅ **Streaming des fichiers** : pas de chargement complet en mémoire pour WebDAV

## Structure
```
LICENSE                          # GPL v3
README.md
.gitignore
build.gradle.kts / settings.gradle.kts / gradle.properties
app/
  build.gradle.kts
  proguard-rules.pro
  src/main/AndroidManifest.xml
  src/main/res/
    values/ (strings, themes, colors)
    xml/ (network_security_config, backup_rules, data_extraction_rules)
    drawable/, mipmap-anydpi-v26/ (icône SyncZen)
  src/main/java/fr/bonobo/synczen/
    MainActivity.kt
    ui/SyncScreen.kt, SyncViewModel.kt, theme/
    worker/SyncWorker.kt
    data/NasConfig.kt, SyncLogEntry.kt, NasConfigDao.kt, SyncLogDao.kt,
         AppDatabase.kt, NasRepository.kt
  src/test/java/fr/bonobo/synczen/data/NasConfigTest.kt
```
