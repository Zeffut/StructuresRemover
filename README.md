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

Le scan ne visite que les chunks **déjà générés** : l'en-tête des fichiers `r.X.Z.mca` est lu
directement pour savoir quels chunks existent. Sans cette précaution, demander un chunk au serveur
le ferait générer, et un scan « map entière » finirait par créer du terrain au lieu de le
parcourir.

## Limites connues

- L'annulation ne restaure qu'**une seule** opération, et seulement tant que le serveur tourne.
  Au-delà de 4 000 000 de blocs modifiés, l'historique est abandonné et `/sr undo` refuse.
- La sélection est limitée à 2 000 000 de blocs.
- `/sr scan world` ne parcourt qu'une dimension : celle où tu te trouves. Le mod force une
  sauvegarde du monde avant de démarrer, pour que les chunks encore en mémoire soient bien pris en
  compte.
- Une seule opération à la fois sur le serveur.
- Les structures mises en file et les sélections vivent en mémoire : un redémarrage du serveur
  les efface.
- Les messages en jeu sont en anglais, pour rester lisibles depuis un client vanilla (pas de
  fichier de langue côté client).

## Compilation

```bash
./gradlew build      # jar dans build/libs/
./gradlew test       # tests des rotations / miroirs / choix de l'ancre
./gradlew runServer  # serveur de dev
```

Java 21 et Gradle 9.7 (fourni par le wrapper) requis.

## Licence

MIT — voir [LICENSE](LICENSE).
