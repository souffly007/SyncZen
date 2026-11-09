# Recette finale SyncZen — disponibilité de l'émulateur

Vérification en lecture du 9 octobre 2026. Aucun téléchargement, AVD créé, installation,
synchronisation réelle ou changement système. Aucun code de l'application modifié.

## Environnement

- SDK du projet : `/var/home/Snoopy63/Android/Sdk` (`local.properties`).
- Android Emulator 37.2.12.0 installé dans `emulator/emulator`.
- ADB, outils de compilation et plateformes Android API 34, 35, 36, 37 présents.
- Aucun AVD retourné par `emulator -list-avds` ; `~/.android/avd` est vide.
- Aucune image de démarrage : répertoire SDK `system-images` absent.
- `cmdline-tools`, `tools`, `sdkmanager` et `avdmanager` absents dans ce SDK et non trouvés
  dans l'environnement Android Studio Flatpak inspecté. Ils ne sont pas nécessaires
  pour la solution graphique via le Device Manager déjà fourni par Android Studio.
- Android Studio Flatpak est installé ; aucune image ni définition AVD trouvée dans
  ses données inspectées. Aucun processus emulator/qemu-system-x86_64 actif sur l'hôte.
- KVM **installé et utilisable sur l'hôte** : `emulator -accel-check` retourne 0,
  « KVM (version 12) is installed and usable. » Le premier contrôle dans le sandbox
  masquait `/dev/kvm` ; cela ne justifie aucune modification de KVM ou du système.

Conclusion : moteur disponible, mais aucun Android virtuel démarrable avec les
images actuellement présentes. Configuration nécessitant téléchargement et création
AVD, donc accord préalable de l'utilisateur.

## Résultats

Les rapports XML existants de l'étape 4 confirment 60 tests locaux réussis, zéro échec,
zéro erreur et zéro test ignoré. Ils n'ont pas été réexécutés pendant cette inspection.
Les APK Debug, Release non signé et tests Android sont présents. Les résultats de
compilation réussis restent ceux de l'étape 4.

| Recette demandée sur Android | Résultat actuel | Couverture locale précédente |
| --- | --- | --- |
| Android Keystore : chiffrement, erreurs, données conservées | Non exécuté | AES-GCM synthétique ; test Android compilé limité au round-trip, IV distincts et erreur d'AAD |
| Room 1 → 2 → 3, interruption et relance | Non exécuté | Migrations synthétiques, conservation et rollback ; mort du processus Android non testée |
| HTTP refusé / HTTPS avec certificat valide | Non exécuté | HTTPS/TLS/Basic/redirections locaux ; test Android compilé vérifiant uniquement la politique, sans requête |
| Sélection SAF et autorisations persistantes | Non exécuté | Arbres DocumentFile fictifs ; pas de sélecteur ni autorisations Android réelles |
| WorkManager : synchronisation, annulation, reprise, concurrence | Non exécuté | Moteur, journal et verrou OS ; pas de jobs WorkManager sur Android |
| Transferts SMB/WebDAV depuis Android | Non exécuté | Samba isolé et HTTPS local, gros fichiers 128 Mio et interruptions |
| Mise à jour ancien Debug → nouveau Debug, même certificat | Non exécuté | Nouveau Debug signé vérifié ; ancien Debug à préparer |

Recette Android : **0 réussite, 0 échec, 7 groupes non exécutés** faute d'image/AVD.
Un test non exécuté n'est pas une réussite. Les deux tests instrumentés déjà présents
ne couvrent pas à eux seuls toute cette recette ; il faudra compléter les scénarios.

## Mise à jour de test

`apksigner verify --print-certs` réussit pour le nouveau APK Debug et l'ancien APK
présent dans `app/release/SyncZen-v0.2.5beta.apk`. Leurs certificats SHA-256 diffèrent :

- Nouveau Debug : `1723fc2d56b64317505fd6925763e52399905010847c23fc95c9eaf6b3b2ca60`.
- Ancien APK du répertoire release : `a2723a3cc2b325db18d8f4b662ec262efbcf6954d8ace8a78e59b9176a7e55f1`.

Cet ancien APK ne peut donc pas servir à une mise à jour en place vers le nouveau
Debug. Aucun changement de signature ou de fichier APK n'a été effectué. Préparer
un APK ancien **Debug** à partir de l'ancien code dans une copie isolée de SyncZen,
avec le même certificat Debug que le nouveau, puis initialiser uniquement des
configurations synthétiques dans l'AVD. Ne pas désinstaller pour contourner une
incompatibilité de signature : cela invaliderait le test de conservation.

## Solution proposée, soumise à accord

Utiliser Android Studio déjà installé : Device Manager → Create Virtual Device,
puis télécharger une image x86_64 API 35 et créer un AVD nommé
`SyncZen-Recette-API35`, sans compte ni données personnels. Aucun outil de virtualisation
ni changement système n'apparaît nécessaire. La sélection exacte de l'image sera
vérifiée dans le catalogue disponible avant téléchargement. Une deuxième recette
sur la version Android du smartphone pourra ensuite être nécessaire.

Références officielles :
[Device Manager et images système](https://developer.android.com/studio/run/managing-avds),
[accélération Linux/KVM](https://developer.android.com/studio/run/emulator-acceleration).

Ensuite, préparer les tests instrumentés manquants, les anciennes versions Debug et
les destinations fictives isolées. Toutes les commandes d'installation devront cibler
explicitement le numéro de série de cet émulateur (`adb -s emulator-…`), jamais un
appareil physique. Les migrations et l'ancienne application seront initialisées hors
réseau ; les transferts ne seront autorisés que vers les serveurs fictifs contrôlés.

## Risques avant installation réelle

Keystore Android réel reste obligatoire et non validé. Les interruptions/morts du
processus lors des migrations, permissions SAF persistantes, contraintes WorkManager,
politique réseau réellement appliquée et mise à jour signée conservant les données
restent non validées sur Android. Les gros transferts HTTP/2 et le comportement mémoire,
espace cache et latence d'annulation doivent aussi être mesurés. Un émulateur ne
prouve pas le comportement du Keystore matériel ou de tous les fournisseurs SAF du
smartphone. La Release courante est non signée ; une mise à jour réelle exigera la
signature correspondant à l'installation existante, sans effacement des données.

Attente d'accord explicite avant tout téléchargement ou création d'AVD, conformément
à la contrainte de l'utilisateur. Aucune installation sur smartphone autorisée.
