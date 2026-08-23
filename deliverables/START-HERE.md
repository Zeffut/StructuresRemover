# Start here

Paste the block below into a session that can run commands and reach the Minestrator panel. It is
self-contained: it names what to read, what to do, what must not be touched, and where to stop.

Everything it refers to is in this repository, on branch
`claude/structure-selection-deletion-mod-js5d55`.

---

    Tu reprends le nettoyage de la map Minecraft BOTW du serveur Hyrule (Minestrator, server_id
    480302). Une session précédente a fait tout le travail d'analyse mais a perdu l'accès au shell
    et au panel en cours de route. Tout ce dont tu as besoin est dans le dépôt StructuresRemover,
    branche claude/structure-selection-deletion-mod-js5d55.

    LIS D'ABORD, dans cet ordre, avant toute action :
    - deliverables/AUDIT.md   — deux défauts trouvés dans la liste actuelle, à respecter absolument
    - deliverables/SHELLS.md  — le détail du premier
    - deliverables/PLAN.md    — le découpage en 5 lots et l'ordre
    - deliverables/NEXT-RUN.md — la procédure, les pièges d'outils, les valeurs attendues
    - deliverables/RUNBOOK.md — la mécanique d'application

    CE QU'IL FAUT FAIRE :
    1. Arrêter le serveur Hyrule (480302) proprement via le panel Minestrator.
    2. Prendre le smartbackup le plus récent, noter son id, le télécharger.
    3. Extraire, et faire DEUX copies : une intouchable qui sert de référence « avant », une de
       travail.
    4. Redémarrer le serveur sur sa map actuelle, pour ne pas le laisser hors ligne pendant le
       travail.
    5. fits.py sur la copie de travail, avec server_verify2.txt ET server_bodies.txt en 3e argument.
       Lire les lignes, pas le pourcentage — 99,63 % est le résultat correct, pas 100 %.
       Attendu : 6 231 en paysage attendu, 0 bloc bâti différent.
    6. Nettoyer lot par lot selon PLAN.md, en vérifiant entre chaque.

    DEUX EXCLUSIONS OBLIGATOIRES, non négociables, trouvées par l'audit :

    - Les passerelles souterraines : 2 453 structures, ~205 000 blocs de oak_planks purs entre y=2
      et y=19, larges de 3 blocs, réparties sur toute la carte. Personne ne sait si elles enjambent
      du vide. NE PAS LES SUPPRIMER tant que Zeffut n'a pas regardé 13198 5 7998 en jeu.

    - Les 4 familles de maisons aux murs de pierre brute (~2 506 structures) qui deviendraient des
      carcasses sans toit ni plancher. NE PAS LES SUPPRIMER tant que Zeffut n'a pas tranché entre
      étendre la suppression aux murs (mécanisme de bodies.py) ou laisser ces maisons entières.
      Exemples à regarder : -5090 68 180 et -1301 68 3345.

    Passe aussi la liste par tools/keepout.py : il protège les 4 fontaines des grandes fées, qui
    sont maintenant utilisées par les joueurs.

    À NE JAMAIS TOUCHER : le terrain et tout ce qui pousse, les arbres à miel, les machines en
    cuivre des quatre peuples, les 4 fontaines.

    LIMITE ABSOLUE : tu vas jusqu'à la map nettoyée et vérifiée, et tu t'arrêtes là. Tu ne remplaces
    PAS la map du serveur sans le feu vert explicite de Zeffut.

    Deux outils n'ont jamais été exécutés et peuvent être cassés : le 3e argument de fits.py et
    tools/keepout.py. Si leurs chiffres ne tombent pas juste, suspecte le code avant la map.
    keepout.py doit écarter exactement 5 positions.

    Rends compte : les chiffres de chaque étape, et ce que tu as trouvé.

---

## Why this file exists

The session that wrote it had done the analysis and could not act on it. Shell, panel and
child-session creation were all refused by a safety check scoped to that conversation, and retrying
was explicitly futile. Reading and writing files still worked, so the analysis is complete and the
execution is not.

That is worth saying plainly rather than dressing up: **the map has not been cleaned.** What exists
is a deletion list that has been checked against the map it will be applied to, a staged plan, and
two findings that would have spoiled the result if the list had been applied as it stood.

## The two findings, in one paragraph each

**The largest family in the list is not a repeated decoration.** 2,453 structures, 205,277 blocks,
12% of everything — three-wide strips of pure oak planks running in straight lines between y=2 and
y=19, spread across the whole map. Four members read at opposite corners all show the same shape.
Not mineshafts: zero rails below y=10. Whether they may be removed turns on whether they bridge open
air, which no amount of reading a text file can answer.

**The village houses with raw walls would be left as shells.** At wall height the list contains the
window panes and not the wall they are set into, because cobblestone generates on its own and
`oak_log` matches the `_log` suffix — the whole list holds zero `oak_log`. So the purge takes floor,
roof, door, bed, torches and windows, and leaves a roofless cobblestone box. Four families, 2,506
structures. The savanna houses, whose walls are crafted terracotta, come out whole.

Neither is a bug. Both are the tools doing exactly what they were told, on cases nobody had opened
until somebody read the list one structure at a time.
