# SyncZen — recette finale Android API 35

9 octobre 2026. Recette réalisée exclusivement dans l'AVD neuf
`SyncZen-Recette-API35`, numéro de série `emulator-5580`. Aucun smartphone physique,
NAS réel, compte personnel, donnée utilisateur ou projet voisin utilisé ou modifié.

## Environnement installé avec l'accord de l'utilisateur

- Disque du SDK : environ **30 Gio disponibles avant téléchargement**, 23 Gio à la fin.
- Archive officielle `system-images;android-35;google_apis;x86_64`, révision 9 :
  **1 738 815 903 octets**, soit 1,74 Go / 1,62 Gio. Estimation communiquée avant
  téléchargement : marge de 10 à 12 Gio pour image et AVD.
- Image décompressée : **3 754 673 936 octets / 3,50 Gio**.
- Source : [catalogue SDK officiel](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml)
  et [archive officielle](https://dl.google.com/android/repository/sys-img/google_apis/x86_64-35_r09.zip).
- Taille et SHA-1 vérifiés contre le catalogue :
  `0103e6dab21290c4b9d16550a3ce99476f884eef`. Chaque membre ZIP extrait dans la
  seule nouvelle image, avec validation des chemins et contrôle CRC de l'extraction.
- Android Emulator **37.2.12.0**, KVM utilisable, Android invité **API 35**.
- Aucun outil supplémentaire installé. Image et AVD créés avec les outils existants.
  Les bibliothèques MockWebServer/okhttp-tls déjà utilisées et en cache ont seulement
  été ajoutées aux dépendances de l'APK de tests Android ; Gradle est resté hors ligne.
- Aucun compte Google configuré. L'image Google APIs fournit ses applications système,
  mais aucun compte ou fichier personnel n'a été importé.

Les démarrages SwiftShader, off, swiftshader_indirect et software ont rencontré des
SIGSEGV dans le moteur de rendu. GDB déjà installé a localisé une pile dans
`gles_swiftshader/libGLESv2.so`. Le mode **ANGLE/swangle** a démarré correctement
(Android a signalé boot_completed=1 et le nom exact de l'AVD). Ce mode est conservé
dans le config.ini du seul AVD dédié. Aucun pilote, module, paquet ou réglage système
modifié pour contourner le crash. La cause profonde du crash graphique n'est pas
établie ; ce n'est pas un échec exécuté dans SyncZen.

À la fin : émulateur et serveur SMB fictif arrêtés, redirection ADB de test retirée.
L'AVD et ses données **entièrement synthétiques** sont conservés. Pour le relancer :

```sh
/var/home/Snoopy63/Android/Sdk/emulator/emulator -avd SyncZen-Recette-API35 -port 5580 -no-window -gpu swangle -no-audio -no-snapshot -no-boot-anim -dns-server 127.0.0.1 -no-metrics
```

Toujours vérifier `adb -s emulator-5580 emu avd name` avant toute installation.
Ne jamais utiliser une commande d'installation sans sélection explicite de cet AVD.

## Résultats des sept groupes

**39 scénarios Android distincts ont chacun réussi, zéro échec restant.** Ils ont été
exécutés en plusieurs phases avec préparation des fixtures ; ce n'est pas le résultat
d'une commande unique lançant toutes les classes. Aucun test n'a été ignoré lors des
passages réussis. La classe de préparation d'arrêt forcé est une fixture volontairement
interrompue et n'est pas comptée parmi les 39 tests réussis.

| Groupe | Résultat réalisé | Limites / non exécuté |
| --- | --- | --- |
| 1. Android Keystore | AES-GCM via AndroidKeyStore sur Android : chiffrement/déchiffrement Unicode, IV distincts, erreur d'AAD, clé synthétique dédiée supprimée puis déchiffrement refusé sans régénération ; erreurs empêchant le remplacement de configuration et ciphertext corrompu conservé. | Pas de validation du Keystore matériel/TEE/StrongBox du smartphone. |
| 2. Room 1 → 2 → 3 | SQLite/Room Android réels : configurations et journaux conservés, ciphertexts v2 conservés, journal de publication après réouverture, configurations v1 vides/multiples, rollback sur erreur de chiffrement et relance. Arrêt forcé réel après la première ligne chiffrée : retour en v1 intact, table temporaire annulée, puis migration v3 réussie. | Pas d'injection à tous les points possibles d'une panne électrique ou du stockage. |
| 3. Réseau Android | Politique globale et par hôte : HTTP refusé. HttpURLConnection HTTP loopback refusé avec erreur cleartext avant connexion. HTTPS, PROPFIND et PUT avec certificat fictif correctement validé par une autorité de test ; certificats non approuvés et mauvais nom refusés ; Basic absent sur HTTP ; 30 scénarios de redirections refusées. Client de production conserve ses composants TLS par défaut. | Le certificat fictif est approuvé uniquement par le client du test. Aucun certificat ni trust-all ajouté à la configuration de production ou au magasin système Android. Acceptation d'une chaîne publique réelle par le client de production non testée sur une destination fictive. |
| 4. SAF | Choix du dossier `Download/SyncZenFiction` dans DocumentsUI via le bouton existant de SyncZen ; consentement Android ; permission read persistée (`persisted=0x1`), après arrêt/redémarrage ; lecture du fichier fictif et URI dans Room. | Autres fournisseurs SAF, stockage amovible et autorisation révoquée non testés. |
| 5. WorkManager | Vrai SyncWorker via WorkManager : fichier SAF publié sur SMB puis relance sans nouvel envoi grâce à Room ; deux déclencheurs exclus par le verrou commun ; travail différé annulé puis nouveau passage ; worker RUNNING annulé pendant une copie SAF de 128 Mio, verrou libéré et cache nettoyé, nouvelle publication complète réussie. | Contraintes périodiques, charge/Wi-Fi, Doze, redémarrage complet de l'AVD, annulation WorkManager pendant PUT/MOVE plutôt que copie SAF non testés. Les annulations directes WebDAV pendant PUT/GET sont testées séparément. |
| 6. SMB/WebDAV | Sur Android : WebDAV local HTTPS, SHA-256, publication sans écrasement, conflits/races, serveurs non conformes, troncature, réponse MOVE perdue et reprise ; PUT artificiels 128 Mio, coupures, nouveau temporaire, erreurs et annulations. SMB via serveur Samba isolé, fichier de 16 Mio, journal Room rouvert sans doublon, refus de création et de renommage avec remplacement ; worker réel publie aussi 128 Mio après annulation SAF. | Gros WebDAV HTTP/2 : non testé (banc 128 Mio en HTTPS HTTP/1.1). Cycle WebDAV complet de 128 Mio avec Worker et trust système non testé. Compatibilité de serveurs NAS réels hors de cette recette, jamais sollicités. |
| 7. Mise à jour Debug | Ancien code v1 (`d85c780`, Initial commit) compilé dans `/tmp/synczen-legacy-debug`. Ancien Debug installé uniquement dans l'AVD, base v1 générée fictive, application ouverte puis mise à jour `install -r` vers le nouveau Debug. Migration v3 et conservation des paramètres, identifiants, URI SAF stockées, charge, SSID, date et journal vérifiées. Aucune désinstallation. | Mise à jour Release réelle non exécutée : Release produite non signée. Ancien APK du dossier release ne partage pas le certificat Debug et n'a pas servi à cette mise à jour. |

Certificat SHA-256 commun des deux APK **Debug** :
`1723fc2d56b64317505fd6925763e52399905010847c23fc95c9eaf6b3b2ca60`.
Le test de mise à jour a vérifié les dates et paramètres avant que les scénarios de
synchronisation fictive ne les modifient normalement. Les permissions SAF réellement
choisies ont été testées ensuite ; les URI anciennes synthétiques ont bien été
conservées par migration, sans prétendre qu'elles portaient une permission réelle.

## Décompte et preuves

| Classe Android | Scénarios réussis |
| --- | ---: |
| AndroidKeystoreCredentialsTest | 2 |
| AndroidCredentialMigrationTest | 7 |
| AndroidNetworkSecurityTest | 2 |
| AndroidWebDavSafePublicationTest | 6 |
| AndroidWebDavSecurityTest | 8 |
| AndroidWebDavStreamingTest | 6 |
| AndroidSafWorkManagerTest | 4 |
| AndroidSmbPublicationTest | 1 |
| AndroidUpgradeTest | 1 |
| AndroidMigrationCrashRecoveryTest | 1 |
| AndroidActiveCancellationTest | 1 |
| **Total distinct** | **39** |

Preuves textuelles conservées dans [`recette-final-results`](recette-final-results/) :

- `synczen-android-core-tests.txt` : **OK (17 tests)**, incluant la mise à jour v1 → v3.
- `synczen-android-functional-tests.txt` : premiers 21 scénarios, dont 3 erreurs du banc
  ensuite corrigées. Les 8 TLS, 6 flux, 1 SMB et 2 réseau de ce passage réussissent.
- `synczen-android-saf-workmanager-tests.txt` : passage intermédiaire, échec du
  worker contre un serveur configuré seulement en anonyme ; les autres cas passent.
- `synczen-android-saf-workmanager-auth.txt` : **OK (4 tests)** après configuration
  de l'authentification du serveur fictif.
- `synczen-android-saf-workmanager-validated.txt` : **OK (4 tests)** sur la dernière
  version du banc, avec les deux fichiers fictifs présents, dont 128 Mio.
- `synczen-android-migration-interruption.txt` : marqueur précis puis « Process crashed »
  attendu, provoqué par `am force-stop` sur la seule application fictive de l'AVD.
- `synczen-android-migration-recovery-tests.txt` : **OK (1 test)**, rollback/reprise.
- `synczen-android-active-cancellation-tests.txt` : **OK (1 test)**, 21,368 secondes.

Les erreurs initiales du banc ne sont pas masquées : arrêt trop précoce après choix
SAF ; attente incorrecte qu'un travail annulé reste consultable après remplacement
(parfois élagué par WorkManager) ; serveur SMB acceptant uniquement l'anonyme alors
que le repository de production utilise NTLM. Après correction du banc, les tests
concernés ont été réellement relancés et ont réussi. Aucun changement du transport
SMB ni d'une autre fonction de production pour faire passer ces tests.

## Compilation et tests locaux

```sh
JAVA_HOME=/var/home/Snoopy63/.jdks/ms-17.0.20 ./gradlew testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest --offline --no-daemon
```

**BUILD SUCCESSFUL, 30 secondes**, Debug et Release, lintVitalRelease et APK Android.
Les rapports XML locaux confirment **60 tests, 0 échec, 0 erreur, 0 ignoré** (les 45
précédents et les 15 de l'étape 4). Une dernière compilation de l'APK de tests après
adaptation du nombre de fichiers SAF réussit en 21 secondes, puis ses quatre tests
SAF/WorkManager ont été exécutés avec succès. `git diff --check` sans erreur.
Avertissement Gradle préexistant de compatibilité future avec Gradle 10.

## Fichiers et isolation

Production Kotlin, interface, schéma Room, migrations et configuration réseau :
**aucun changement pendant cette recette**.

Modifié : `app/build.gradle.kts` pour rendre accessibles aux tests Android les deux
bibliothèques de tests déjà présentes en cache. Tests instrumentés Keystore et réseau
complétés ; ajout des classes listées ci-dessus et d'une fixture distincte de mort
du processus. Cette fixture ne doit jamais être lancée comme une suite normale.

Scripts dans `tools/recette/` : image/AVD, ancienne base synthétique, sélecteur SAF,
Samba isolé, authentification fictive, interruption et arrêt des processus de test.
Ils vérifient les chemins ou le nom de l'AVD avant leurs opérations sensibles.
Les scénarios instrumentés utilisent le matériel Android `ranchu` comme garde ; ils
ne sont pas destinés à être lancés sur smartphone.

Samba 4.24.7 existant écoute seulement sur 127.0.0.1, port dynamique 55459 pour cette
recette. ADB reverse exposait le port 16445 uniquement dans l'AVD. Configurations,
partage, passdb et journaux sont dans `/tmp/synczen-recette-smb-zylqpxxy`. Un alias
`fiction-worker` et son mot de passe fictif sont configurés seulement dans son passdb
privé ; le propriétaire Unix sert uniquement à l'accès au partage temporaire.
Aucun compte Unix, mot de passe système, identifiant NAS personnel ou passdb Samba
système modifié. Aucun smbd existant arrêté. Les fichiers distants fictifs restent
conservés, sans DELETE introduit dans SyncZen.

## Décision avant installation réelle

La validation **AndroidKeyStore sur Android**, auparavant manquante, a été exécutée
avec succès sur cet émulateur. Elle ne valide pas le matériel cryptographique du
smartphone ou sa configuration précise. L'installation sur smartphone reste non
réalisée et non autorisée par cette recette.

Restent à examiner avant une distribution : version Android du smartphone, exigences
TLS/certificat de la destination, espace local pour un snapshot complet, signature
Release correspondant à l'application installée et stratégie de sauvegarde conservant
l'association données/Keystore. Une copie Room seule n'est pas une sauvegarde portable
des secrets chiffrés. Ne pas désinstaller, effacer les données ni rétrograder le schéma.

Recettes complémentaires possibles exclusivement en environnement fictif : image de
la version Android du smartphone (accord avant nouveau téléchargement), chaîne TLS
avec trust système sur serveur fictif, gros WebDAV HTTP/2 et cycle complet Worker,
conditions WorkManager périodiques/Doze/reboot et interruption réseau SMB, révocation
SAF/autres fournisseurs. Elles sont **non exécutées**, sans les compter comme réussites.
Les mesures ne constituent pas un profil mémoire/énergie du smartphone ; 64 Kio est
le tampon applicatif, pas la consommation totale du processus.
