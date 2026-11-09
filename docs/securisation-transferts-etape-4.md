# SyncZen — étape 4 : transferts volumineux et interruptions

Date : 9 octobre 2026. Intervention limitée au projet SyncZen.

## Modifications, fichier par fichier

| Fichier | Modification de l'étape 4 |
| --- | --- |
| `app/src/main/java/fr/bonobo/synczen/data/BoundedTransfer.kt` | Nouveau : copie avec tampon de 64 Kio, contrôles d'annulation avant et après lecture ; fermeture du flux bloquant lors d'une annulation ; corps OkHttp alimenté depuis le fichier cache, taille en `Long`, sans tableau proportionnel au fichier et déclaré non rejouable. |
| `app/src/main/java/fr/bonobo/synczen/data/WebDavSafeTransport.kt` | Remplacement de `file.readBytes()` par le corps en flux. Chaque appel reste lié à l'annulation jusqu'à la fermeture de la réponse, y compris pendant GET/SHA-256 et PROPFIND. Annulation par `Call.cancel()`, fermeture avec `use`, conversion des erreurs réseau dues à l'annulation en annulation. Lecture XML toujours limitée à 1 Mio, maintenant avec contrôles d'annulation. |
| `app/src/main/java/fr/bonobo/synczen/data/SmbSafeTransport.kt` | Copie via le même tampon borné et contrôles d'annulation ; fermeture du flux SMB actif sur annulation pendant lecture/écriture. Création exclusive et renommage sans remplacement inchangés. |
| `app/src/main/java/fr/bonobo/synczen/data/LocalStaging.kt` | Nouveau : snapshots dans le cache privé dédié `synczen-staging-v1`, création exclusive avec UUID ; vérification préalable de l'espace disponible lorsque la taille est connue ; nettoyage des seuls orphelins locaux reconnus, réguliers, sans suivre les liens symboliques. |
| `app/src/main/java/fr/bonobo/synczen/worker/SyncWorker.kt` | Utilise la copie bornée et la fermeture sur annulation pour les lectures SAF ; utilise le cache dédié sous le verrou existant ; supprime le snapshot local du transfert en `finally`. Une erreur d'écriture du journal d'interruption ne masque plus l'annulation. |
| `app/src/test/java/fr/bonobo/synczen/data/BoundedTransferTest.kt` | Nouveau : 8 tests de flux, SHA-256, erreurs, annulations et nettoyage local prudent. |
| `app/src/test/java/fr/bonobo/synczen/data/WebDavStreamingTest.kt` | Nouveau : 6 tests HTTPS local de gros PUT, coupure, relance, annulation et erreur serveur. |
| `app/src/test/java/fr/bonobo/synczen/data/SmbSafePublicationTest.kt` | Ajout d'une publication complète artificielle de 128 Mio sur Samba isolé, vérification SHA-256 et relance sans second upload. Les 3 tests précédents restent présents. |
| `docs/securisation-transferts-etape-4.md` | Ce bilan. |

Aucun changement d'interface, de schéma Room, de migration, de stockage des identifiants,
de configuration TLS/HTTPS, de signature ou de version de l'application à cette étape.
Les rapports des étapes précédentes restent des comptes rendus historiques.

## Fonctionnement et garanties conservées

Un fichier source est copié vers un snapshot sur disque, avec un seul tampon de
64 Kio pour cette copie. Les transferts sont séquentiels sous le verrou partagé.
Le PUT lit ensuite ce snapshot par blocs de 64 Kio. Les buffers internes OkHttp/Okio,
TLS et système s'ajoutent à ce tampon : 64 Kio n'est pas une promesse concernant
la mémoire totale du processus. Le contenu d'un fichier complet n'est plus chargé
dans un `ByteArray` en production. Les petites sondes et le XML borné restent en mémoire.

Le snapshot nécessite toujours de l'espace disque proportionnel au fichier. Ce choix
préserve une source stable entre calcul SHA-256, envoi et comparaison distante, et
évite d'envoyer une source SAF partiellement lue. Une taille connue incohérente ou
une date source modifiée bloque la publication comme auparavant. L'espace libre
est vérifié avant copie lorsque la taille est connue ; une saturation pendant la
copie reste possible et produit une erreur sans publication.

Le moteur `SafeFilePublisher` et le journal Room de l'étape 3 sont conservés :

1. Calcul SHA-256 et taille du snapshot, avec lecture bornée.
2. Contrôle de la destination finale et du journal existant. Un contenu final
   correspondant à la source ET au journal est reconnu comme déjà publié, sans PUT.
   Cela fonctionne aussi après une réponse MOVE perdue ou un commit final Room échoué.
3. Enregistrement de l'intention avant tout envoi.
4. Création exclusive d'un temporaire distant UUID dans le même parent.
5. Relecture SHA-256 du temporaire ; aucune publication si incomplet ou différent.
6. Publication sans remplacement, puis contrôle du contenu final et validation du journal.

WebDAV conserve `If-None-Match: *` pour PUT, `Overwrite: F` et `If-Match` fort pour MOVE,
les sondes de conformité du serveur, HTTPS obligatoire, l'absence de redirections et
la validation TLS normale. Les reprises automatiques réseau restent désactivées et
le corps de fichier est déclaré `isOneShot()`. SMB conserve `O_EXCL`, SMB2/3 et
`renameTo(target, false)`. Aucun repli avec écrasement n'a été ajouté.

L'annulation est propagée au worker ; la réponse et les flux sont fermés même en
cas d'erreur. Pour WebDAV, un observateur d'annulation reste actif pendant l'appel
et la consommation de sa réponse, puis est arrêté et rejoint. Pour SAF et les flux
SMB, un observateur tente de fermer le flux bloquant ; la copie vérifie l'annulation
entre les blocs. Une annulation pendant une publication déjà exécutée côté serveur
peut laisser son résultat incertain : le journal permet de le vérifier au passage
suivant, sans annuler la publication par suppression ni réenvoyer aveuglément.

## Temporaires et reprises prudentes

Aucun DELETE, nettoyage ou écrasement distant. Les temporaires interrompus, les
résultats incertains et les sondes sont conservés. Une relance après transfert
incomplet repart du début vers un nouveau temporaire UUID ; il n'y a pas de reprise
à l'octet, de réutilisation d'un temporaire distant ni de nettoyage distant automatique.
Les fichiers utilisateur et les anciennes sauvegardes à plat restent conservés.
Un fichier source modifié à un chemin final existant reste un conflit explicite.

Le nettoyage local intervient sous `SyncExecutionGate`, donc hors de tout autre
transfert SyncZen actif. Il ne concerne que les fichiers réguliers dont le nom
correspond exactement à `stage-<UUID>.part` dans le répertoire privé dédié.
Les fichiers aux autres noms, répertoires, liens symboliques, cibles de liens et
anciens temporaires à plat de l'étape 3 ne sont pas nettoyés. Le répertoire dédié
est refusé s'il est un lien symbolique. Une suppression d'orphelin impossible
arrête le passage avec erreur ; elle ne provoque aucune réinitialisation de données.

Le journal ne dispense pas de relire les contenus local et distant pour les
confirmer. Les passages suivants évitent les nouveaux PUT inutiles mais conservent
ce coût de lecture, de disque et de réseau.

## Validation locale

Commande de validation finale :

```sh
JAVA_HOME=/var/home/Snoopy63/.jdks/ms-17.0.20 ./gradlew testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest --offline --no-daemon
```

**BUILD SUCCESSFUL en 41 secondes. 60 tests réussis, zéro échec, zéro erreur,
zéro test ignoré : les 45 précédents et 15 nouveaux.** Debug et Release compilent ;
`lintVitalRelease` réussit. L'APK de tests Android compile mais n'a pas été installé
ni exécuté. `git diff --check` est sans erreur. Aucun `readBytes()` ou `copyTo()`
ne subsiste dans le code principal des transferts.

| Suite | Tests réussis |
| --- | ---: |
| CredentialCipherTest | 3 |
| CredentialMigrationTest | 7 |
| NasConfigTest | 3 |
| WebDavSecurityTest | 8 |
| RemotePathPolicyTest | 4 |
| SourceTreeScannerTest | 3 |
| SafeFilePublisherTest | 8 |
| WebDavSafePublicationTest | 6 |
| SmbSafePublicationTest | 4 |
| BoundedTransferTest | 8 |
| WebDavStreamingTest | 6 |
| Total | 60 |

Rapports générés : `app/build/reports/tests/testDebugUnitTest/index.html` et fichiers
XML dans `app/build/test-results/testDebugUnitTest/`. APK :
`app/build/outputs/apk/debug/app-debug.apk`,
`app/build/outputs/apk/release/app-release-unsigned.apk`,
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
Avertissement Gradle préexistant sur des fonctionnalités incompatibles avec Gradle 10.

Les nouveaux scénarios couvrent :

- Fichier de 128 Mio généré sur disque par répétition d'un bloc artificiel de 64 Kio :
  corps en flux, taille correcte, taille maximale des écritures contrôlée et SHA-256
  identique à la lecture de référence.
- PUT de 128 Mio sur HTTPS local : `Content-Length`, nombre total d'octets reçus,
  création exclusive et absence de stockage du corps dans le serveur fictif.
- Coupure pendant le PUT : erreur, absence de rejeu automatique ; nouvelle tentative
  complète avec un nom temporaire distinct.
- Annulation pendant un PUT dont le serveur ne lit pas le corps, pendant l'attente
  des en-têtes et pendant un GET/SHA-256 ralenti : terminaison testée sous 1,5 seconde.
- Lecture source fictive bloquée : fermeture sur annulation, aucun octet écrit.
- Erreur de lecture : fermeture des deux flux et arrêt après le bloc déjà écrit.
- Annulation entre blocs : pas de lecture supplémentaire, fermeture des flux.
- Corps déjà annulé, snapshot absent ou modifié et erreur du sink : erreur ou
  annulation explicite, aucun succès masqué.
- HTTP 507 : erreur de création, aucun rejeu ni MOVE.
- Orphelin local propre à SyncZen nettoyé ; fichier étranger, ancien cache, répertoire,
  lien symbolique et cible préservés ; espace insuffisant et racine symbolique refusés.
- Publication SMB complète de 128 Mio : temporaire et final vérifiés par SHA-256,
  relance avec journal correspondant sans deuxième envoi.

Les tests précédents couvrent aussi les migrations Room synthétiques 1 → 3 et
2 → 3, la conservation des identifiants chiffrés, des paramètres NAS, SAF, dates et
journaux, les erreurs de déchiffrement, HTTPS/Basic/TLS/redirections, les chemins,
les collisions, les fichiers existants, les interruptions, les réponses perdues,
les commits Room échoués et l'exclusion des synchronisations concurrentes.

Le banc volumineux utilise HTTPS avec HTTP/1.1 exclusivement côté serveur fictif.
Le lecteur HTTP/2 de MockWebServer 4.12 ne respecte pas `bodyLimit`, contrairement
à son lecteur HTTP/1.1 : [source primaire MockWebServer 4.12](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/mockwebserver/src/main/kotlin/okhttp3/mockwebserver/MockWebServer.kt).
Les premiers essais ont rencontré des délais expirés et des erreurs du banc HTTP/2.
Le banc a également été corrigé pour utiliser 127.0.0.1 explicitement, éviter une
résolution localhost vers IPv6 et retarder les en-têtes dans le scénario où le
serveur ne lit pas le corps. Un import ambigu `IOException` dans les nouveaux
tests a été corrigé. Ces problèmes ne restent pas des échecs de la suite finale.
Aucun changement des protocoles du client de production. Les anciens tests WebDAV
conservent leur négociation HTTP/2 ; les gros fichiers HTTP/2 restent à valider
avec un autre serveur fictif représentatif.

Tous les fichiers, certificats et identifiants de tests sont artificiels. Les
serveurs n'écoutent que sur la boucle locale ; le partage Samba, ses configurations,
états et journaux sont dans un répertoire temporaire isolé. Aucun accès au NAS réel,
aucune installation, aucun changement d'un autre projet ou d'un service Samba système.

## Limites et vérifications obligatoires avant smartphone

- Android Keystore réel reste **obligatoire avant toute installation sur smartphone**.
  Les tests Android sont seulement compilés. Aucun système d'émulateur installé
  n'était disponible lors des étapes précédentes ; aucun appareil n'a été utilisé.
- Valider sur émulateur isolé avec configurations synthétiques : Keystore et migrations
  Android réelles, refus du HTTP par la politique réseau, certificats/nom d'hôte,
  permissions SAF persistantes et WorkManager, annulation et mort brutale du processus.
- Mesurer la mémoire, le temps, l'espace cache et l'annulation avec gros fichiers sur
  Android. Les tests JVM prouvent les tailles des blocs et les résultats locaux,
  pas les performances ou la latence d'un smartphone.
- Tester de gros transferts HTTP/2 avec un serveur fictif indépendant. Le grand PUT
  HTTPS local et le cycle complet SMB sont testés ; le cycle WebDAV complet avec
  SHA-256/publication est couvert par les fichiers plus petits des tests précédents.
- La fermeture d'un flux SAF ou JCIFS est une tentative d'interruption, pas une garantie
  pour tous les fournisseurs/serveurs. Certains appels de métadonnées SMB et opérations
  bloquantes peuvent attendre le délai réseau de la bibliothèque. Aucun thread n'est
  forcé et aucune configuration n'est supprimée. Ces latences doivent être vérifiées
  sur les environnements Android fictifs représentatifs.
- Les fournisseurs SAF dont la taille/date est inconnue ne permettent pas de détecter
  toute lecture prématurément terminée. L'intégrité du contenu effectivement lu,
  du temporaire et du final est vérifiée comme à l'étape 3.
- Les garanties de non-remplacement WebDAV dépendent toujours de la conformité réelle
  du serveur ; les sondes ne prouvent pas tous ses comportements ni sa durabilité.
  Les limites de compatibilité JCIFS de l'étape 3 restent applicables.
- Prévoir l'espace local pour un snapshot entier et l'espace distant pour la nouvelle
  arborescence, les sauvegardes anciennes et les temporaires conservés. Leur nettoyage
  distant doit être une future opération explicitement validée après inspection.
- Mise à jour future : même applicationId et même signature, installation en place,
  sans désinstallation ni effacement des données. Préparer une sauvegarde adaptée au
  Keystore : une copie Room seule ne rend pas les identifiants déchiffrables ailleurs.
  Premier lancement hors réseau pour contrôler les migrations et configurations avant
  toute autorisation de synchronisation. Une ancienne version ne lit pas le schéma v3.
  L'APK Release produit ici est non signé et n'est pas un paquet prêt à installer.

Arrêt après l'étape 4 pour validation. Aucune synchronisation réelle n'a été déclenchée.
