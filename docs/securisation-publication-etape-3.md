# SyncZen — chemins et publication sans écrasement, étape 3

Validation locale du 9 octobre 2026. Aucun véritable NAS ou identifiant personnel
utilisé. Aucune synchronisation de l'application déclenchée, aucun smartphone connecté
ou application installée. Les tests ciblent des transports fictifs, des arbres SAF
simulés et des serveurs liés uniquement à 127.0.0.1. Les états et partages Samba sont
créés dans des dossiers temporaires distincts, sans utiliser le service Samba système.

## Comportement livré

Une destination contient désormais les nouveaux fichiers sous :

```text
SyncZen-v3/source-<SHA-256 de l'URI SAF>/chemin/relatif/fichier
```

L'identifiant est stable pour une même URI SAF, même après redémarrage ou retrait puis
réajout de cette URI. Le nom du dossier racine ne sert pas d'identifiant. Les fichiers
homonymes de sources différentes et de sous-dossiers différents restent séparés.
Les anciennes sauvegardes à plat ne sont ni déplacées, ni supprimées, ni remplacées.
La première synchronisation future recopiera les fichiers dans la nouvelle arborescence ;
les anciens fichiers resteront présents. Il faut prévoir cet espace supplémentaire.

Les segments refusent notamment les traversées, séparateurs, caractères de contrôle,
points/espaces terminaux, noms Windows réservés, caractères ambigus pour les URL et
noms réservés aux temporaires SyncZen. Les segments WebDAV sont ajoutés avec le
constructeur HttpUrl, sans concaténation libre. Les chemins des destinations sont
contrôlés avant publication, y compris les traversées et séparateurs encodés.
Les collisions de casse, Unicode NFC et fichier/dossier sont détectées avant envoi.
Une source invalide ne retourne aucun candidat de cette source. Les collisions
persistantes sont aussi contrôlées dans le suivi local au fil des passages.

Avant publication :

1. Le worker prend un verrou de fichier partagé par tous les déclencheurs.
2. Il parcourt les sources distinctes et conserve leurs chemins relatifs.
3. Il copie le flux source dans son propre fichier cache temporaire. Une erreur de
   lecture, une taille connue incohérente ou un changement de date source bloque l'envoi.
4. Le contenu local reçoit une empreinte SHA-256 et une taille.
5. Si le fichier final existe, seuls un contenu identique ET un enregistrement local
   correspondant permettent de le reconnaître comme déjà publié. Sinon, conflit.
6. L'intention est persistée dans Room avant envoi. Le transfert utilise un nom
   temporaire UUID unique, créé exclusivement, dans le même parent que le fichier final.
7. Le contenu temporaire distant est relu et comparé à l'empreinte locale.
8. La publication utilise une opération distante sans remplacement. Le fichier final
   est relu puis l'enregistrement marqué publié.

Un fichier local modifié au même chemin qu'un fichier distant existant provoque donc
un conflit, même si l'ancien fichier a été publié par SyncZen. Aucun remplacement ou
versionnement automatique n'a été ajouté. Aucun DELETE distant n'est exécuté.
Seuls les temporaires fraîchement créés par le transfert peuvent être renommés.
L'ancienne méthode d'envoi à plat est bloquée et n'est plus appelée par le worker.

Si la réponse de publication ou l'écriture finale Room est perdue, une relance vérifie
le contenu final et l'intention persistée : elle marque la publication achevée sans
nouveau transfert. Un fichier inconnu, même de contenu identique, reste un conflit.
Tous les passages parcourent les sources et vérifient le contenu ; ils ne reposent
plus exclusivement sur une date globale, notamment pour initialiser la nouvelle
arborescence. Les dates existantes sont conservées par migration ; une réussite
complète enregistre ensuite la date de début du passage.

## Garanties des transports et limites

### SMB / JCIFS-NG 2.1.10

L'archive source de la version utilisée a été examinée :

- `O_EXCL` sans `O_TRUNC` sélectionne la disposition SMB2 `FILE_CREATE` ;
- `renameTo(target, false)` transmet `replaceIfExists=false` via
  `FileRenameInformation2`, sur le même arbre/partage ;
- aucun repli vers une copie avec remplacement ou une suppression préalable ;
- SMB2/SMB3 restent obligatoires selon la configuration existante du client.

Référence primaire :
[code JCIFS SmbFile](https://github.com/AgNO3/jcifs-ng/blob/jcifs-ng-2.1.10/src/main/java/jcifs/smb/SmbFile.java).
Les tests contre Samba 4.24.7 confirment le refus de création d'un temporaire existant
et le refus de renommage vers une destination existante. Aucun serveur NAS réel n'a
été essayé. Certains serveurs exigeant des fonctions que JCIFS 2.1.10 ne prend pas en
charge, notamment le chiffrement SMB obligatoire, peuvent refuser la connexion.

### WebDAV / OkHttp 4.12.0

`PUT` temporaire porte `If-None-Match: *`. `MOVE` porte `Overwrite: F`, une Destination
HTTPS de même origine et `If-Match` avec l'ETag fort du temporaire vérifié. Seul un
statut de création 201 est accepté comme publication nouvelle ; les refus, statuts
ambigus, redirections, absence d'ETag fort ou garanties non vérifiables bloquent l'opération.
Les reprises automatiques OkHttp sont désactivées pour ce transport de publication.
HTTPS, certificats système, contrôle du nom d'hôte et interdiction des redirections
restent ceux de l'étape 2.

Référence primaire : [RFC 4918, sections 9.9.3 et 10.6](https://www.rfc-editor.org/rfc/rfc4918#section-10.6).
La garantie dépend du serveur WebDAV, pas d'OkHttp seul. Avant la première publication
nouvelle d'un passage, deux petites ressources fictives UUID vérifient que le serveur
refuse les PUT et MOVE qui tenteraient de les remplacer. La sonde contrôle aussi la
conservation de leur contenu. Un échec est mémorisé pour ce transport afin de ne pas
répéter les sondes pour chaque fichier. Aucun fichier source n'est envoyé lorsque
cette vérification échoue.

Cette sonde ne prouve pas la conformité du serveur dans toutes les circonstances,
ni sa durabilité face à une panne physique. Le contrôle porte sur le namespace/les
URL et les opérations protocole ; il ne permet pas de prouver la topologie physique
ou l'absence de liens/jonctions internes au serveur. La destination doit donc être un
espace de sauvegarde maîtrisé. Un serveur qui change de comportement ou ignore les
conditions reste hors des garanties que le client peut imposer.

Les sondes et temporaires interrompus sont conservés intentionnellement. Ils pourront
nécessiter un nettoyage explicite futur, après inspection ; aucun nettoyage distant
automatique risquant de supprimer un fichier inconnu n'est fourni.

## Fichiers concernés par cette étape

Modifiés :

- `app/src/main/java/fr/bonobo/synczen/data/AppDatabase.kt` : version 3 et migration additive.
- `app/src/main/java/fr/bonobo/synczen/data/NasRepository.kt` : transports sûrs, résultat déjà publié,
  ancien envoi à plat refusé.
- `app/src/main/java/fr/bonobo/synczen/worker/SyncWorker.kt` : verrou commun, parcours relatif,
  staging local, publication contrôlée, journal et messages de conflit/interruption.
- `app/src/main/java/fr/bonobo/synczen/ui/SyncViewModel.kt` : travail manuel unique avec KEEP,
  prise en charge du résultat déjà publié ; aucune disposition d'interface changée.
- `app/src/test/java/fr/bonobo/synczen/data/CredentialMigrationTest.kt` : migrations vers v3
  et conservation du suivi après réouverture.
- `app/src/test/java/fr/bonobo/synczen/data/WebDavSecurityTest.kt` : tests HTTPS/Basic/redirections
  adaptés aux PUT temporaires, sans affaiblir les contrôles de l'étape 2.

Ajoutés :

- `app/src/main/java/fr/bonobo/synczen/data/RemotePathPolicy.kt`
- `app/src/main/java/fr/bonobo/synczen/data/SourceTreeScanner.kt`
- `app/src/main/java/fr/bonobo/synczen/data/SafeFilePublisher.kt`
- `app/src/main/java/fr/bonobo/synczen/data/SmbSafeTransport.kt`
- `app/src/main/java/fr/bonobo/synczen/data/WebDavSafeTransport.kt`
- `app/src/main/java/fr/bonobo/synczen/data/SyncExecutionGate.kt`
- `app/src/main/java/fr/bonobo/synczen/data/SyncFileRecord.kt`
- `app/schemas/fr.bonobo.synczen.data.AppDatabase/3.json`
- `app/src/test/java/fr/bonobo/synczen/data/RemotePathPolicyTest.kt`
- `app/src/test/java/fr/bonobo/synczen/data/SourceTreeScannerTest.kt`
- `app/src/test/java/fr/bonobo/synczen/data/SafeFilePublisherTest.kt`
- `app/src/test/java/fr/bonobo/synczen/data/WebDavSafePublicationTest.kt`
- `app/src/test/java/fr/bonobo/synczen/data/SmbSafePublicationTest.kt`
- Ce compte rendu.

La migration 2 → 3 ajoute uniquement la table `sync_file`. La migration 1 → 2 chiffrant
les identifiants reste présente, suivie de 2 → 3. Aucun fallback destructif. Les
chiffrements existants, paramètres NAS, SAF, SSID, dates et journaux sont conservés.

## Résultats finaux

```sh
JAVA_HOME=/var/home/Snoopy63/.jdks/ms-17.0.20 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline --no-daemon
```

BUILD SUCCESSFUL. Debug et Release compilent ; lintVital Release réussit. L'APK de
tests Android compile mais n'a pas été installé ou exécuté. Release reste non signé.

45 tests exécutés : 45 réussites, zéro échec, zéro test ignoré.

- 3 tests AES-GCM ;
- 7 tests de migration/configuration Room, incluant 1 → 3 et 2 → 3, conservation des
  ciphertexts v2, paramètres, journaux et intentions de publication après réouverture ;
- 3 tests NasConfig existants ;
- 8 tests HTTPS/Basic/TLS/redirections de l'étape 2, avec leurs 30 scénarios de redirection ;
- 4 tests de chemins : stabilité, homonymes, traversées, encodages, noms ambigus,
  collisions de casse/Unicode/fichier-dossier, temporaires uniques ;
- 3 tests du parcours SAF sur DocumentFile fictifs avec Robolectric : arborescences,
  sources multiples, permissions, noms invalides/absents, cycles et alias de casse ;
- 8 tests du moteur : publication, absence d'écrasement, conflits externes et locaux,
  transferts tronqués/interrompus, relances, réponses perdues, échecs de commit Room,
  annulation et exclusion de deux threads simulant les déclencheurs manuel/périodique ;
- 6 tests HTTPS WebDAV contre MockWebServer : publication imbriquée, legacy conservé,
  création concurrente avant MOVE, serveurs non conformes/ETag absent/MOVE indisponible,
  troncature, réponse MOVE perdue et racines dangereuses ;
- 3 tests JCIFS contre un serveur Samba isolé : homonymes entre sources, dossiers
  imbriqués, fichiers existants, refus de création exclusive et de renommage avec
  remplacement interdit, temporaires incomplets non publiés et relances sans doublon.

Les premiers passages ont révélé et corrigé : validation insuffisante d'un séparateur
encodé dans la racine ; certificat fictif ne couvrant pas le nom localhost du serveur
local ; restrictions des stubs Android sur les helpers JVM ; isolation des processus
et permissions du banc Samba. Un arrêt du banc Samba a interrompu un premier daemon
Gradle : Samba est désormais lancé dans une session dédiée avec setsid. Ses chemins
d'état, RPC, journaux et fichiers sont tous temporaires ; les permissions adaptées
concernent exclusivement son partage fictif. Aucun changement de configuration
Samba système, de service système ou de projet voisin.

`git diff --check` est sans erreur. Avertissement Gradle existant concernant Gradle 10.

## Limites restantes et arrêt

- Android Keystore réel et politique réseau Android restent à valider sur un émulateur
  isolé avant toute installation sur smartphone. Le parcours SAF réel et WorkManager
  sur appareil n'ont pas été exécutés ; les tests de verrou portent sur le verrou OS
  réellement utilisé et des threads locaux, pas sur des jobs WorkManager Android.
- Les fournisseurs SAF aux métadonnées inconnues ne permettent pas de détecter toute
  lecture prématurément terminée ; les tailles connues et changements de dates sont
  contrôlés, et l'intégrité source lue/temporaire/final est comparée.
- Les vérifications relisent le contenu local et distant, donc peuvent coûter du temps
  et du réseau. Les fichiers cache requièrent de l'espace disque et peuvent subsister
  après une mort brutale du processus.
- WebDAV utilise encore `file.readBytes()` pour le PUT temporaire : le risque mémoire
  des gros fichiers est expressément réservé à l'étape 4, qui n'a pas été engagée.
- Avant une future mise à jour : même signature et applicationId, pas de désinstallation
  ou effacement, premier lancement hors réseau, migrations v3 à valider en environnement
  Android fictif. Une ancienne application ne sait pas lire le schéma version 3.

Arrêt après l'étape 3. Aucune publication ou synchronisation réelle n'a été autorisée
ou déclenchée pendant cette intervention.
