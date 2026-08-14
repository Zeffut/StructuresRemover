# StructuresRemover

Mod Fabric pour Minecraft **1.21.11**. On sélectionne une structure comme avec WorldEdit (hache en
bois, clic gauche / clic droit), puis le mod balaye la map entière et supprime **toutes les copies
identiques** de cette structure.

Cas d'usage typique : une map générée avec la même maison / la même ruine / le même bâtiment posé
des centaines de fois, et l'envie de tout dégager d'un coup.

## Installation

Le mod est **server-side** : toute la logique tourne côté serveur et aucun item custom n'est
enregistré, donc les joueurs peuvent se connecter avec un client vanilla.

1. Installer [Fabric Loader](https://fabricmc.net/use/) 0.17+ pour Minecraft 1.21.11
2. Déposer [Fabric API](https://modrinth.com/mod/fabric-api) dans `mods/`
3. Déposer `structuresremover-1.0.0.jar` dans `mods/`

Toutes les commandes demandent le niveau d'opérateur **2**.

## Utilisation

### Sélectionner

```
/sr wand                     active la baguette (donne une hache en bois)
                             clic gauche  -> coin 1
                             clic droit   -> coin 2
/sr pos1 [x y z]             coin sur le bloc que tu VISES (jusqu'à 128 blocs)
/sr pos2 [x y z]
/sr expand <n> [direction]   agrandit la sélection (direction : up/down/north/... ou all)
/sr contract <n> [direction] la rétrécit
/sr trim                     la resserre pile sur les blocs, sans la marge d'air
/sr sel                      affiche la sélection courante
/sr clear                    efface la sélection
/sr outline <true|false>     contour en particules (activé par défaut)
```

La sélection est affichée en permanence par un contour de particules vertes, visible depuis un
client vanilla. `/sr pos1` sans argument vise le bloc que tu regardes : plus besoin d'aller te
placer sur chaque coin.

### Mettre plusieurs structures en file

```
/sr add [nom]                met la sélection de côté (nom auto : structure1, structure2…)
/sr list                     liste les structures en file
/sr forget <nom> | all       en retire une, ou tout
```

Tu peux sélectionner une maison, `/sr add maison`, sélectionner une tour, `/sr add tour`, puis
lancer **un seul** scan qui cherche les deux en même temps. Limite : 16 structures.

Si la file est vide, `/sr scan` et `/sr remove` utilisent simplement la sélection courante.

### Structures qui se répètent sans être identiques

Sur une vraie map, une structure posée cent fois n'est presque jamais copiée bloc pour bloc : elle
est encastrée dans un terrain différent, décorée autrement, tournée. Un seul exemplaire comme
modèle ne retrouve alors qu'une fraction des copies.

La réponse est d'en donner **plusieurs exemplaires sous le même nom** :

```
/sr add sanctuaire     (sélection autour d'une première copie)
/sr add sanctuaire     (autour d'une deuxième)
/sr add sanctuaire     (autour d'une troisième…)
```

À chaque ajout, le mod aligne automatiquement le nouvel exemplaire sur les précédents (rotation
comprise) et compte, case par case, combien d'exemplaires sont d'accord. Il en tire deux choses :

- **ce qui doit correspondre** — les cases sur lesquelles *tous* les exemplaires s'accordent,
  c'est-à-dire le cœur invariant de la structure ;
- **ce qui sera supprimé** — les cases présentes dans *la majorité* des exemplaires. Le terrain,
  différent sous chaque copie, disparaît de ce périmètre tout seul.

Trois à cinq exemplaires suffisent en général. Un exemplaire qui ne s'aligne sur rien est refusé
plutôt que d'affaiblir le motif, et `/sr add` indique combien de cases restent communes.

**Prends des exemplaires variés** — dans des terrains différents. Deux copies voisines posées sur
le même sol apprennent ce sol comme faisant partie de la structure, et plus rien ne correspond
ailleurs.

À partir de deux exemplaires, le mod déduit aussi **les matériaux propres à la structure** : les
blocs dont la plupart des occurrences tombent sur des cases où les exemplaires sont d'accord. Le
terrain échoue à ce test par nature — il y en a beaucoup et deux copies creusées dans des collines
différentes n'en alignent qu'une fraction. La suppression efface alors, à l'intérieur de chaque
copie, tout bloc fait de ces matériaux : c'est ce qui emporte les parties qui varient (entrée,
décoration) sans toucher au sol. Réglable avec `/sr set clearmaterials false`.

### Persistance

La sélection courante, les structures mises en file et les réglages **survivent au redémarrage du
serveur**. Ils sont écrits dans la sauvegarde du monde, sous
`<monde>/structuresremover/<uuid>.dat` : un fichier NBT compressé par joueur, où les blocs sont
stockés sous forme de palette plus un index par case.

Les données vivent dans le monde, pas à côté du mod, parce qu'une structure mémorise les
coordonnées où elle a été capturée — les transporter vers un autre monde n'aurait aucun sens.

L'historique d'annulation (`/sr undo`), lui, n'est **pas** conservé : il décrit des blocs qui
peuvent avoir changé entre deux démarrages.

### Chercher et supprimer

```
/sr scan radius <chunks>     compte les copies sans rien toucher
/sr scan world               idem, sur toute la dimension
/sr remove radius <chunks>   supprime les copies trouvées
/sr remove world             idem, sur toute la dimension

/sr status                   avancement du job en cours
/sr cancel                   arrête le job en cours
/sr undo                     restaure la dernière suppression
/sr options                  affiche les réglages
/sr set <option> <valeur>    modifie un réglage
```

Déroulé classique :

```
/sr wand
(clic gauche sur un coin, clic droit sur le coin opposé)
/sr sel                 -> vérifier les dimensions de la boîte
/sr scan world          -> voir combien de copies existent avant de casser quoi que ce soit
/sr remove world
```

Le scan tourne toujours dans **la dimension où tu te trouves**. Une structure capturée dans le
Nether peut donc être cherchée dans l'Overworld.

`scan` est volontairement non destructif : **fais toujours un `scan` avant un `remove`**, et fais
une sauvegarde de ta map.

## Réglages (`/sr set`)

| Option | Défaut | Effet |
| --- | --- | --- |
| `rotations` | `false` | cherche aussi les copies tournées de 90°, 180°, 270° |
| `mirrors` | `false` | cherche aussi les copies en miroir |
| `tolerance` | `100` | pourcentage de blocs qui doivent correspondre (100 = copie exacte) |
| `matchair` | `false` | l'air de la sélection doit aussi être de l'air dans le monde |
| `fill` | `air` | bloc mis à la place de la structure supprimée |
| `keeporiginal` | `true` | ne touche pas à la copie d'où vient la sélection |
| `removeentities` | `false` | supprime aussi les entités présentes dans la copie (cadres, armor stands…) |
| `clearmaterials` | `true` | efface aussi les blocs faits des matériaux de la structure (motifs à plusieurs exemplaires) |
| `maxmatches` | `0` | s'arrête après N copies (`0` = illimité) |
| `chunkspertick` | `8` | chunks lus par tick pendant le scan — à baisser si le serveur lag |
| `blockspertick` | `20000` | blocs écrits par tick pendant la suppression |

Par défaut le mod cherche donc la structure **exacte, dans la même orientation**, en **ignorant
l'air** : seuls les blocs de la structure comptent. Une copie contre laquelle un arbre a poussé
reste une copie, et une sélection tracée large autour du bâtiment marche quand même.

Mettre `matchair true` rend le mod plus strict : le vide autour de la structure doit alors être
vide dans le monde aussi.

Baisser `tolerance` sert quand les structures ont été un peu modifiées. Attention : plus la
tolérance est basse, plus le risque de faux positif est grand.

## Comment ça marche

1. **Capture** — la sélection est lue bloc par bloc et stockée comme un tableau de block states.
   Tant que `matchair` est à `false`, le pattern est automatiquement rogné sur les blocs qu'il
   contient réellement : la marge d'air autour est jetée. Le contenu des block entities (coffres,
   panneaux…) est ignoré : deux maisons dont les coffres ont un loot différent comptent quand même
   comme identiques.
2. **Variantes** — si les rotations/miroirs sont demandés, le pattern est décliné dans les
   orientations correspondantes. Les variantes identiques (structure symétrique) sont éliminées.
3. **Ancre** — pour chaque variante, le mod choisit le bloc le plus rare de la structure et évite
   les blocs de terrain courants (pierre, terre, eau…). C'est ce bloc que le scan cherche.
4. **Scan** — les chunks sont parcourus par petits paquets à chaque tick serveur. Toutes les
   structures en file et toutes leurs orientations sont indexées par leur bloc d'ancre, donc **une
   seule passe** sur le monde les cherche toutes. Une section de chunk dont la palette ne contient
   aucune ancre est sautée d'un bloc, sans lire ses 4096 blocs.
5. **Suppression** — les copies trouvées sont remplacées par le bloc `fill`, sans mises à jour de
   voisinage ni drops, et l'ancien état est mémorisé pour `/sr undo`.

Le scan ne visite que les chunks **qui existent réellement**, c'est-à-dire l'union de deux
sources : l'en-tête des fichiers `r.X.Z.mca` (ce qui est sur le disque) et les chunks actuellement
chargés par le serveur. Les deux sont nécessaires — un chunk qui vient d'être généré ou modifié
peut rester longtemps en mémoire avant d'atteindre un fichier de région, et `saveAll` ne le force
pas de manière fiable. Un parcours disque seul saute donc précisément la zone où le joueur
travaille.

Sans cette précaution, demander un chunk au serveur le ferait générer, et un scan « map entière »
finirait par créer du terrain au lieu de le parcourir.

## Quand le scan ne trouve rien

La ligne de progression indique en direct **le nombre de chunks réellement lus** et **le nombre de
fois où le bloc-clé de la structure a été vu**. C'est ce qui permet de trancher :

| Ce que tu vois | Ce que ça veut dire |
| --- | --- |
| `0 chunks read` | la zone n'est pas générée — va y faire un tour, ou scanne ailleurs |
| `N chunks read, 0 key blocks` | le bloc-clé n'existe pas dans la zone : mauvais rayon, ou mauvaise dimension |
| `N chunks read, beaucoup de key blocks, 0 copies` | les copies **ne sont pas identiques** |

Le dernier cas est le plus fréquent sur une vraie map. Les causes habituelles :

- **les copies sont tournées** — par défaut le mod ne cherche que la même orientation, essaie
  `/sr set rotations true`
- **la sélection inclut le terrain** sous la structure : chaque copie repose sur un sol différent,
  donc plus rien ne correspond. Resserre avec `/sr trim`, ou refais la sélection sans le sol.
- **les blocs se sont adaptés au décor** : escaliers, barrières, murs et feuilles changent d'état
  selon leurs voisins. `/sr set tolerance 95` laisse passer ces écarts.

Le mod affiche aussi ce diagnostic à la fin d'un scan resté vide.

## Limites connues

- L'annulation ne restaure qu'**une seule** opération, et seulement tant que le serveur tourne.
  Au-delà de 4 000 000 de blocs modifiés, l'historique est abandonné et `/sr undo` refuse.
- La sélection est limitée à 2 000 000 de blocs.
- `/sr scan world` ne parcourt qu'une dimension : celle où tu te trouves. Le mod force une
  sauvegarde du monde avant de démarrer, pour que les chunks encore en mémoire soient bien pris en
  compte.
- Une seule opération à la fois sur le serveur.
- `/sr undo` ne survit pas à un redémarrage (les sélections et les structures, si).
- Les messages en jeu sont en anglais, pour rester lisibles depuis un client vanilla (pas de
  fichier de langue côté client).

## Compilation

```bash
./gradlew build       # jar dans build/libs/
./gradlew test        # tests des rotations / miroirs / choix de l'ancre
./gradlew runServer   # serveur de dev
./gradlew runSelftest # lance un serveur, y pose des structures et vérifie la détection
```

`runSelftest` couvre ce que les tests unitaires ne peuvent pas atteindre : il démarre un vrai
serveur, y construit des structures et leurs copies, puis fait tourner le vrai scan dessus. Cinq
scénarios : copies proches, structure à cheval sur deux chunks, structure faite de blocs de
terrain courants, parcours « map entière » par fichiers de région, et structure sans aucune copie.

La persistance se teste sur deux démarrages successifs :

```bash
./gradlew runSelftest -Dsrpersist=write    # écrit sélection, structures et réglages
./gradlew runSelftest -Dsrpersist=verify   # redémarre et vérifie que tout est revenu
```

Java 21 et Gradle 9.7 (fourni par le wrapper) requis.

## Licence

MIT — voir [LICENSE](LICENSE).
